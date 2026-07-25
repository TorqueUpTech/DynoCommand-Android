package com.dynocommand.app.protocol

/**
 * The fixed 10-byte dyno CAN packet. Byte-identical to the original firmware and
 * the verified PC implementation. Bytes are held as Int (0..255) for clean
 * arithmetic; converted to/from signed bytes only at the wire boundary.
 */
class CanPacket {
    var id: Int = 0
    var cmd: Int = 0
    val payload = IntArray(4)
    var tsl: Int = 0
    var tsh: Int = 0
    var crcl: Int = 0
    var crch: Int = 0

    /** Little-endian 16-bit value carried in payload[2..3]. */
    var value16: Int
        get() = (payload[2] and 0xFF) or ((payload[3] and 0xFF) shl 8)
        set(v) {
            payload[2] = v and 0xFF
            payload[3] = (v ushr 8) and 0xFF
        }

    fun toInts(): IntArray =
        intArrayOf(id, cmd, payload[0], payload[1], payload[2], payload[3], tsl, tsh, crcl, crch)

    fun toHex(): String = toInts().joinToString(" ") { "%02X".format(it and 0xFF) }

    companion object {
        fun fromInts(b: IntArray): CanPacket {
            val p = CanPacket()
            p.id = b[0]; p.cmd = b[1]
            p.payload[0] = b[2]; p.payload[1] = b[3]; p.payload[2] = b[4]; p.payload[3] = b[5]
            p.tsl = b[6]; p.tsh = b[7]
            if (b.size > 8) { p.crcl = b[8]; p.crch = b[9] }
            return p
        }
    }
}
