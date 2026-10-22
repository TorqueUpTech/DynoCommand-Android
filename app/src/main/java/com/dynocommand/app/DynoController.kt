package com.dynocommand.app

import com.dynocommand.app.protocol.CanPacket
import com.dynocommand.app.protocol.CommandState
import com.dynocommand.app.protocol.DynoMode
import com.dynocommand.app.protocol.DynoState
import com.dynocommand.app.protocol.FrameParser
import com.dynocommand.app.protocol.PacketFactory
import com.dynocommand.app.protocol.PacketFramer
import com.dynocommand.app.protocol.PacketParser
import com.dynocommand.app.protocol.SpeedControl
import com.dynocommand.app.protocol.SpeedUnit
import com.dynocommand.app.transport.Transport
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.timer
import kotlin.math.max

/** Brake auto-releases after this long (2 minutes). */
const val BRAKE_AUTO_OFF_MS = 120_000L

/**
 * Owns the active transport, the ~20 Hz TX loop (mirroring COM_Comms.txThread),
 * and the RX framer/parser. Plain volatile fields are read by the UI on its own
 * refresh tick. Handshake/identity replies the parser produces are queued on the
 * RX thread and flushed on the TX thread so all writes stay serialized.
 */
class DynoController {
    val command = CommandState()
    val state = DynoState()

    private var transport: Transport? = null
    private val rx = FrameParser { onPacket(it) }
    private var txTimer: Timer? = null
    private var tick5 = 0

    private val responses = ConcurrentLinkedQueue<CanPacket>()

    // Loss-of-comms tally (dropouts after a successful connect) + optional event sink.
    @Volatile var commsLossEvents = 0L; private set
    var commsLogEnabled = false
    /** When set, comms events (UP/LOST/RESTORED/…) are forwarded here for file logging. */
    var commsLogSink: ((String) -> Unit)? = null
    private var commsActive = false
    private var commsEverUp = false

    @Volatile var txPackets = 0L; private set
    @Volatile var rxPackets = 0L; private set
    val rxErrors: Long get() = rx.errors
    val isConnected: Boolean get() = transport != null
    var transportName = "—"; private set

    @Volatile var lastTxHex = "—"; private set
    @Volatile var lastRxHex = "—"; private set

    // Optional packet log + live inventory of every distinct packet type seen.
    var logEnabled = false
    private val log = ConcurrentLinkedQueue<String>()

    class PacketStat {
        @Volatile var count = 0L
        @Volatile var last = 0
        @Volatile var min = Int.MAX_VALUE
        @Volatile var max = Int.MIN_VALUE
        @Volatile var lastHex = ""
    }
    private val stats = ConcurrentHashMap<Int, PacketStat>()
    val statsView: Map<Int, PacketStat> get() = stats
    fun clearStats() = stats.clear()

    fun connect(t: Transport): Boolean {
        disconnect()
        t.onData = { rx.feed(it, it.size) }
        if (!t.open()) { t.onData = null; return false }
        transport = t
        transportName = t.description
        txPackets = 0; rxPackets = 0
        commsLossEvents = 0; commsActive = false; commsEverUp = false
        if (commsLogEnabled) commsLogSink?.invoke("CONNECTED — $transportName")
        txTimer = timer(name = "tx", period = 50) { txTick() }
        return true
    }

    fun disconnect() {
        txTimer?.cancel(); txTimer = null
        transport?.let { it.onData = null; it.close() }
        transport = null
        if (commsEverUp) commsLogSink?.invoke("DISCONNECTED")
        responses.clear()
        state.resetForDisconnect()
    }

    // Each fresh->stale RX transition increments the loss tally and logs it.
    private fun trackComms(now: Long) {
        val fresh = state.lastRxMillis != 0L && (now - state.lastRxMillis) < COMMS_TIMEOUT
        if (fresh) {
            if (!commsActive) {
                commsActive = true
                if (commsEverUp) commsLogSink?.invoke("COMMS RESTORED")
                else { commsEverUp = true; commsLogSink?.invoke("COMMS UP — first data received") }
            }
        } else if (commsActive && commsEverUp) {
            commsActive = false
            commsLossEvents++
            commsLogSink?.invoke("COMMS LOST (#$commsLossEvents)")
        }
    }

    private fun onPacket(p: CanPacket) {
        rxPackets++
        lastRxHex = p.toHex()

        val key = (p.id shl 16) or (p.cmd shl 8) or p.payload[0]
        val st = stats.getOrPut(key) { PacketStat() }
        st.count++
        st.last = p.value16
        if (p.value16 < st.min) st.min = p.value16
        if (p.value16 > st.max) st.max = p.value16
        st.lastHex = lastRxHex

        PacketParser.process(p, state, command) { responses.add(it) }
        appendLog("RX", lastRxHex)
    }

    private fun send(p: CanPacket?) {
        val t = transport ?: return
        if (p == null) return
        val f = PacketFramer.wrap(p)
        t.write(f)
        txPackets++
        lastTxHex = p.toHex()
        appendLog("TX", lastTxHex)
    }

    /**
     * Safety governor: the commanded speed (targetSpeed, sent by the knob) is
     * leashed to within ±10 km/h of the actual dyno speed, and may not be
     * commanded to 0 while the dyno is doing ≥5 km/h. The operator's request
     * (requestedSpeed) is fed in gradually. Gated to road speed control; engine/
     * rpm passes through unchanged.
     */
    private fun govern() {
        val c = command
        if (c.speedControl == SpeedControl.Engine || c.speedUnits == SpeedUnit.Rpm) {
            c.targetSpeed = c.requestedSpeed
            return
        }
        val reqKph = c.toKph(c.requestedSpeed)
        val actKph = state.speedKph
        var floor = if (actKph < 5.0) 0.0 else 5.0
        floor = max(floor, actKph - 10.0)
        val ceil = actKph + 10.0
        c.targetSpeed = c.fromKph(reqKph.coerceIn(floor, ceil))
    }

    /**
     * Brake auto-release: the brake must not stay on indefinitely, so after
     * BRAKE_AUTO_OFF_MS it drops back to No Load and pulses the brake-clear bit,
     * exactly as a manual release does. Runs on the TX tick so it fires even
     * while the Activity is backgrounded. Mirrors MainViewModel's brake timer.
     */
    private fun autoReleaseBrake(now: Long) {
        val c = command
        if (c.mode != DynoMode.Brake) { c.brakeOnSinceMs = 0L; return }
        if (c.brakeOnSinceMs == 0L) c.brakeOnSinceMs = now
        if (now - c.brakeOnSinceMs >= BRAKE_AUTO_OFF_MS) {
            c.mode = DynoMode.NoLoad
            c.brakeClearRequests = 6
            c.brakeOnSinceMs = 0L
        }
    }

    private fun txTick() {
        try {
            // Flush queued handshake/identity replies, then run the comms-loss
            // fail-safe (zeroes stale speed, updates device-online flags).
            while (true) { val r = responses.poll() ?: break; send(r) }
            val now = System.currentTimeMillis()
            state.updateWatchdog(now)
            trackComms(now)

            // Mirror PC-controlled shootout state so the knob sends the PC's ramp rate.
            command.shootoutActive = state.shootoutActive
            command.pcRampRate = state.pcRampRate

            autoReleaseBrake(now)
            govern()
            send(PacketFactory.knob(command))
            send(PacketFactory.digitalInputs(command))
            // Bed position: sent only while a Set-Bed button is held (dead-man), so
            // releasing stops the bed. Matches the original's held-button behaviour.
            if (command.mode == DynoMode.SetBed && command.bedMoveActive)
                send(PacketFactory.requestedBedPosition(command))

            if (++tick5 >= 4) {
                tick5 = 0
                if (command.is4Wd) send(PacketFactory.balance(command))
                send(PacketFactory.ping())
                send(PacketFactory.hfrMode(command))
                send(PacketFactory.dynoMode(command))
            }
        } catch (_: Exception) {
        }
    }

    private fun appendLog(dir: String, hex: String) {
        if (!logEnabled) return
        log.add("$dir  $hex")
        while (log.size > 20000) log.poll()
    }

    fun logSnapshot(): List<String> = log.toList()
    fun clearLog() = log.clear()

    companion object {
        private const val COMMS_TIMEOUT = 1500L
    }
}
