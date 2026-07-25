package com.dynocommand.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.dynocommand.app.transport.BluetoothSppTransport
import com.dynocommand.app.transport.TcpTransport
import com.dynocommand.app.transport.Transport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Process-wide owner of the dyno connection. Lives beyond any Activity, so the
 * link survives screen-off and app-switching (the process is kept alive by
 * DynoConnectionService). Holds the single DynoController plus a watchdog thread
 * that re-establishes the Bluetooth link automatically whenever it drops, with
 * exponential backoff.
 */
object DynoLink {
    val controller = DynoController()

    @Volatile var wantConnected = false; private set
    @Volatile var targetMac = ""; private set
    @Volatile var status = "Disconnected"; private set

    private var app: Context? = null
    private var watchdog: Thread? = null
    @Volatile private var linkLost = false
    private var commsLogFile: File? = null

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        controller.commsLogSink = { line -> commsLog(line) }
    }

    /** Begin (and keep) a connection to [mac]. Idempotent; updates the target. */
    fun connect(mac: String) {
        targetMac = mac
        wantConnected = true
        linkLost = false
        commsLogFile = null
        saveState()
        startWatchdog()
    }

    fun disconnect() {
        wantConnected = false
        saveState()
        controller.disconnect()
        status = "Disconnected"
        watchdog?.interrupt()
    }

    /** Reconnect after a process restart if a session was active. */
    fun resumeIfActive() {
        val p = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        if (p.getBoolean("active", false)) {
            val mac = p.getString("mac", "") ?: ""
            if (mac.isNotEmpty()) connect(mac)
        }
    }

    val isLive: Boolean get() = controller.isConnected && !linkLost

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT ensured by the Activity before connect()
    private fun attempt(): Boolean {
        val ctx = app ?: return false
        return try {
            // An IP:port (or localhost:port) in the address field routes to a transparent TCP link
            // (same 0xA0 packet stream) — e.g. a same-PC simulator, no Bluetooth needed; a MAC uses
            // Bluetooth SPP as before.
            val ep = TcpTransport.parseEndpoint(targetMac)
            val t: Transport = if (ep != null) {
                TcpTransport(ep.first, ep.second)
            } else {
                val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
                BluetoothSppTransport(adapter.getRemoteDevice(normalizeMac(targetMac)))
            }
            t.onClosed = { linkLost = true }
            linkLost = false
            controller.connect(t)
        } catch (e: Exception) {
            false
        }
    }

    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = thread(name = "dyno-link", isDaemon = true) {
            var backoff = 1000L
            while (wantConnected) {
                if (!controller.isConnected || linkLost) {
                    status = "Reconnecting…"
                    controller.disconnect()
                    if (attempt()) {
                        status = "Connected"
                        backoff = 1000L
                        sleepQuiet(800)
                    } else {
                        sleepQuiet(backoff)
                        backoff = (backoff * 2).coerceAtMost(15000L)
                    }
                } else {
                    status = "Connected"
                    sleepQuiet(800)
                }
            }
        }
    }

    private fun sleepQuiet(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun saveState() {
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("active", wantConnected)?.putString("mac", targetMac)?.apply()
    }

    // Settings store the bare hex MAC; Android's getRemoteDevice needs colons.
    private fun normalizeMac(raw: String): String {
        val hex = raw.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 12 || !hex.all { it in "0123456789ABCDEF" }) return raw.uppercase()
        return hex.chunked(2).joinToString(":")
    }

    private fun commsLog(line: String) {
        try {
            val ctx = app ?: return
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            if (commsLogFile == null) {
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                commsLogFile = File(dir, "dyno-comms-$ts.log")
            }
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            commsLogFile!!.appendText("$time  $line\n")
        } catch (_: Exception) {
        }
    }

    private const val PREFS = "dyno_link"
}
