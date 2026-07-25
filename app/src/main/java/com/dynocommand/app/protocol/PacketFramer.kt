package com.dynocommand.app.protocol

/**
 * Stream (de)framing for the SPP link.
 * Frame: [0xA0 SYNC][LEN][stuffed 10-byte packet]. Every 0xA0 inside the body
 * is followed by an inserted 0x00. LEN is the total wrapped length (12..22).
 * Direct port of the verified PacketWriter.wrappPacket / PacketTester.
 */
object PacketFramer {
    const val SYNC = 0xA0

    /** Computes CRC, then wraps the 10-byte packet for the wire. */
    fun wrap(pkt: CanPacket): ByteArray {
        val crc = Crc16.calculate(pkt.toInts(), 8)
        pkt.crcl = crc and 0xFF
        pkt.crch = (crc ushr 8) and 0xFF

        val packet = pkt.toInts() // 10 ints incl. CRC
        val buf = IntArray(2 + packet.size * 2 + 1)
        var num = 2
        for (b in packet) {
            buf[num++] = b
            if ((b and 0xFF) == SYNC) buf[num++] = 0
        }
        buf[0] = SYNC
        buf[1] = num

        return ByteArray(num) { (buf[it] and 0xFF).toByte() }
    }
}

/** Byte-at-a-time push parser. Invokes [onPacket] for each valid frame. */
class FrameParser(private val onPacket: (CanPacket) -> Unit) {
    private enum class State { WaitSyncL, WaitSyncH, GetData }

    private var state = State.WaitSyncL
    private val data = IntArray(22)
    private var idx = 0
    var errors: Long = 0
        private set

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) feed(bytes[i].toInt() and 0xFF)
    }

    fun feed(rx: Int) {
        var error = false
        when (state) {
            State.WaitSyncL ->
                if (rx == PacketFramer.SYNC) { data[0] = PacketFramer.SYNC; state = State.WaitSyncH }
                else errors++

            State.WaitSyncH ->
                if (rx > 11 && rx < 23) { data[1] = rx; idx = 2; state = State.GetData }
                else { errors++; error = true }

            State.GetData -> {
                data[idx++] = rx
                if (idx == data[1]) {
                    error = !tryComplete()
                    state = State.WaitSyncL
                    idx = 0
                }
            }
        }
        if (error) state = State.WaitSyncL
    }

    private fun tryComplete(): Boolean {
        val len = data[1]
        val packet = IntArray(10)
        var rxIdx = 0
        var loop = 2
        while (loop < len) {
            if (data[loop] == PacketFramer.SYNC) {
                if (data[loop + 1] != 0 || loop == len - 1) { errors++; return false }
                if (rxIdx >= 10) { errors++; return false }
                packet[rxIdx++] = PacketFramer.SYNC
                loop++ // skip inserted 0x00
            } else {
                if (rxIdx >= 10) { errors++; return false }
                packet[rxIdx++] = data[loop]
            }
            loop++
        }

        val packetCrc = (packet[9] shl 8) or packet[8]
        if (packetCrc != Crc16.calculate(packet, 8)) { errors++; return false }

        onPacket(CanPacket.fromInts(packet))
        return true
    }
}
