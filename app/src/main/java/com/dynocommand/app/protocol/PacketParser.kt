package com.dynocommand.app.protocol

import kotlin.math.min

/**
 * Decodes inbound packets into DynoState, and produces the handshake/identity
 * replies the dyno expects (config echo, config query, FW version/CRC, BT address
 * exchange) via the [respond] callback. Mirrors the verified PC parser.
 */
object PacketParser {
    // Reported firmware version (handheld identity). Mirrors SYS_Version.
    private const val FW_MAJOR = 1
    private const val FW_MINOR = 0
    private const val FW_SUB = 0

    private var tempBtAddress = ""

    fun process(p: CanPacket, st: DynoState, cmd: CommandState, respond: ((CanPacket) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        st.lastRxMillis = now

        val src = p.id and 0xF0
        val lo = p.id and 0x0F

        // ---- Raw roller speeds (pit controller alive). ----
        if (src == 0x20 && p.cmd == 0x0F && p.payload[0] == 0x0A) {
            st.rawSpeedMaster = p.value16; st.pitSeenMillis = now
        } else if (src == 0x70 && p.cmd == 0x0F && p.payload[0] == 0x5B) {
            st.rawSpeedSlave = p.value16; st.pitSeenMillis = now
        }

        // Combined controller speed (avg when both present, >=160 raw threshold).
        val num: Int = if (st.rawSpeedMaster >= 160) {
            if (st.rawSpeedSlave >= 160) (st.rawSpeedMaster + st.rawSpeedSlave) / 2 else st.rawSpeedMaster
        } else {
            if (st.rawSpeedSlave >= 160) st.rawSpeedSlave else 0
        }
        st.speedRevsPerSec = num * 0.002 / cmd.gearRatio
        st.speedKph = st.speedRevsPerSec * cmd.rollerCircumference * 3.6

        // ---- Pit brake state. ----
        if (p.cmd == 0x0F &&
            ((src == 0x20 && p.payload[0] == 0x1A) || (src == 0x70 && p.payload[0] == 0x56))
        ) {
            st.brakeState = if ((p.value16 and 0x200) == 0x200) "Active" else "Requested"
            st.pitSeenMillis = now
        }

        // ---- Hands-free state machine. ----
        if (p.cmd == 0x25) {
            when (p.payload[0]) {
                0xA0 -> { st.handsfreeState = "Accelerate to start speed"; st.pitSeenMillis = now }
                0xA1 -> { st.handsfreeState = "Open throttle"; st.pitSeenMillis = now }
                0xA2 -> { st.handsfreeState = "Ramping up"; st.pitSeenMillis = now }
                0xA3 -> { st.handsfreeState = "Ramping down"; st.pitSeenMillis = now }
                0xA4 -> { st.handsfreeState = "Close throttle"; st.pitSeenMillis = now }
                0xA5 -> { st.handsfreeState = "Slow to start speed"; st.pitSeenMillis = now }
            }

            // PC shootout mode (from the PC node, id == 0x0F).
            if (p.id == 0x0F) {
                when (p.payload[0]) {
                    0x10 -> { st.shootoutMode = "Normal"; st.shootoutActive = false; st.pcSeenMillis = now }
                    0x11 -> { st.shootoutMode = "Standard shootout"; st.shootoutActive = true; st.pcSeenMillis = now }
                    0x12 -> { st.shootoutMode = "Accredited shootout"; st.shootoutActive = true; st.pcSeenMillis = now }
                }
            }
        }

        // ---- PC-dictated ramp rate (used during a shootout). 0F A0 67. ----
        if (p.id == 0x0F && p.cmd == 0xA0 && p.payload[0] == 0x67) {
            st.pcRampRate = p.value16 * 0.002 * 10.0 / cmd.gearRatio; st.pcSeenMillis = now
        }

        // ---- EDM-commanded speed-unit change. 0F 6F 60, P2==1. ----
        if (p.id == 0x0F && p.cmd == 0x6F && p.payload[0] == 0x60 && p.payload[2] == 1) {
            when (p.payload[3]) {
                1 -> { cmd.speedUnits = SpeedUnit.Kph; st.dynoRequestedUnit = "km/h" }
                2 -> { cmd.speedUnits = SpeedUnit.Mph; st.dynoRequestedUnit = "mph" }
                3 -> { cmd.speedUnits = SpeedUnit.Rpm; st.dynoRequestedUnit = "rpm" }
            }
        }

        // ---- Bed position read-back. ----
        if (p.cmd == 0xA0 && (lo == 0x04 || lo == 0x0F) && src == 0x80 && p.payload[0] == 0x32) {
            st.bedCurrentPosition = p.value16 / 1000.0; st.bedSeenMillis = now
        } else if (p.cmd == 0xA0 && (lo == 0x04 || lo == 0x0F) && src == 0x00 && p.payload[0] == 0x31) {
            st.bedTargetPosition = p.value16 / 1000.0
        }

        // ---- Config push from the dyno (CMD 0x2F). ----
        if ((lo == 0x0F || lo == 0x04) && p.cmd == 0x2F) handleConfigPush(p, st, cmd, respond)

        // ---- Config query from the dyno (CMD 0x3F) — we answer. ----
        if ((lo == 0x0F || lo == 0x04) && p.cmd == 0x3F) handleConfigQuery(p, cmd, respond)

        // ---- Firmware version / CRC request (CMD 0xE5). ----
        if (lo == 0x04 && p.cmd == 0xE5 && p.payload[0] == 0x20 && respond != null) respond(fwVersion(p.id))
        if (lo == 0x04 && p.cmd == 0xE5 && p.payload[0] == 0x30 && respond != null) respond(fwCrc())

        // ---- Bluetooth address exchange handshake (CMD 0xD1). ----
        if (lo == 0x04 && p.cmd == 0xD1 && (p.payload[0] and 0x20) == 0) handleBtAddress(p, st, cmd, respond)
    }

    private fun handleConfigPush(p: CanPacket, st: DynoState, cmd: CommandState, respond: ((CanPacket) -> Unit)?) {
        st.configReceived = true
        when (p.payload[0]) {
            0x05 -> { // bed-mode bits + transmission type
                val bits = p.value16
                st.in4wdMode = (bits and 0x01) == 0x01
                st.masterIsController = (bits and 0x02) == 0x02
                st.isA4wdBed = (bits and 0x04) == 0x04
                st.transmission = TransmissionType.values()[min(p.payload[3], 7)]
                cmd.is4Wd = st.isA4wdBed // let the dyno drive the 4WD flag
            }
            0xC9 -> { st.dynoSpeedMaxKph = p.value16 / 10.0; cmd.speedMaxKph = st.dynoSpeedMaxKph }
            0xCA -> { st.dynoSpeedMaxMph = p.value16 / 10.0; cmd.speedMaxMph = st.dynoSpeedMaxMph }
            0xCB -> { st.dynoSpeedMaxRpm = p.value16.toDouble(); cmd.speedMaxRpm = st.dynoSpeedMaxRpm }
            0xCC -> { st.featureLevel = p.value16; cmd.featureLevel = st.featureLevel }
            0xCD -> { st.demoMode = p.payload[2] == 1; cmd.demoMode = st.demoMode }
            0xCF -> { // roller diameter, two-part
                if ((p.payload[1] and 0x7F) == 1) {
                    val d = (p.value16 * 65536.0 + st.tempRollerDiameterHi) / 100000.0
                    st.dynoRollerDiameter = d
                    if (d > 0) cmd.rollerDiameter = d // dyno drives the scaling
                } else {
                    st.tempRollerDiameterHi = p.value16
                }
            }
        }

        // Echo an ACK back when the dyno asked for one (P1 bit 0x80).
        if ((p.payload[1] and 0x80) == 0x80 && respond != null) {
            val ack = CanPacket().apply { id = 0x40 + (p.id shr 4); this.cmd = 0x2F; tsl = p.tsl; tsh = p.tsh }
            ack.payload[0] = p.payload[0]
            ack.payload[1] = p.payload[1] and 0x7F
            ack.payload[2] = p.payload[2]
            ack.payload[3] = p.payload[3]
            respond(ack)
        }
    }

    private fun handleConfigQuery(p: CanPacket, cmd: CommandState, respond: ((CanPacket) -> Unit)?) {
        if (respond == null) return

        val v: Int = when (p.payload[0]) {
            0xC8 -> 3 // PDA model (generic)
            0xC9 -> (cmd.speedMaxKph * 10.0).toInt()
            0xCA -> (cmd.speedMaxMph * 10.0).toInt()
            0xCB -> cmd.speedMaxRpm.toInt()
            0xCC -> cmd.featureLevel
            0xCD -> if (cmd.demoMode) 1 else 0
            0xCF -> { // roller diameter (two-part)
                val scaled = (cmd.rollerDiameter * 100000.0).toLong()
                val hi = scaled / 65536L
                (if ((p.payload[1] and 0x7F) == 1) (scaled - hi * 65536L) else hi).toInt()
            }
            else -> return
        }

        val r = CanPacket().apply { id = 0x40 + (p.id shr 4); this.cmd = 0x3F; tsl = p.tsl; tsh = p.tsh }
        r.payload[0] = p.payload[0]
        r.payload[1] = p.payload[1] and 0x7F
        r.value16 = v and 0xFFFF
        respond(r)
    }

    private fun handleBtAddress(p: CanPacket, st: DynoState, cmd: CommandState, respond: ((CanPacket) -> Unit)?) {
        if (respond == null) return

        if ((p.payload[0] and 0x10) == 0) {
            // Dyno is asking for our EDM address — answer in two halves.
            val addr = cmd.edmAddress
            if (addr.length < 12) return
            val r = CanPacket().apply { id = 0x40 + (p.id shr 4); this.cmd = 0xD1 }
            if ((p.payload[0] and 0x0F) == 0) {
                r.payload[0] = 0x20
                r.payload[1] = hex(addr, 6); r.payload[2] = hex(addr, 8); r.payload[3] = hex(addr, 10)
            } else {
                r.payload[0] = 0x21
                r.payload[1] = hex(addr, 0); r.payload[2] = hex(addr, 2); r.payload[3] = hex(addr, 4)
            }
            respond(r)
            return
        }

        // Dyno is sending us an address to learn — echo each half back.
        val half = "%02X%02X%02X".format(p.payload[1], p.payload[2], p.payload[3])
        val ack = CanPacket().apply { id = 0x40 + (p.id shr 4); this.cmd = 0xD1 }
        ack.payload[1] = p.payload[1]; ack.payload[2] = p.payload[2]; ack.payload[3] = p.payload[3]
        if ((p.payload[0] and 0x0F) == 0) {
            ack.payload[0] = 0x30
            tempBtAddress = half
        } else {
            ack.payload[0] = 0x31
            st.learnedBtAddress = half + tempBtAddress
        }
        respond(ack)
    }

    private fun fwVersion(reqId: Int): CanPacket {
        val r = CanPacket().apply { id = 0x40 + (reqId shr 4); cmd = 0xE5 }
        r.payload[0] = 0x25
        r.payload[1] = FW_MAJOR
        r.payload[2] = ((FW_MINOR and 0x0F) shl 4) + ((FW_SUB and 0xF0) shr 4)
        r.payload[3] = (FW_SUB and 0x0F) shl 4
        return r
    }

    private fun fwCrc(): CanPacket {
        val r = CanPacket().apply { id = 0x40; cmd = 0xE5 }
        r.payload[0] = 0x35
        r.payload[1] = 0xFF
        r.value16 = 0
        return r
    }

    private fun hex(s: String, start: Int): Int = s.substring(start, start + 2).toInt(16)
}
