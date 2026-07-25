package com.dynocommand.app.protocol

/**
 * CRC-16/MODBUS (reflected, poly 0xA001, init 0xFFFF, no final XOR), over the
 * first 8 bytes of a CanPacket. table[1] == 0xC0C1 matches the original firmware.
 */
object Crc16 {
    private val table = ShortArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
            t[i] = c.toShort()
        }
    }

    fun calculate(buffer: IntArray, length: Int, accumulator: Int = 0xFFFF): Int {
        var acc = accumulator and 0xFFFF
        for (i in 0 until length) {
            val idx = (acc xor (buffer[i] and 0xFF)) and 0xFF
            acc = (acc ushr 8) xor (table[idx].toInt() and 0xFFFF)
            acc = acc and 0xFFFF
        }
        return acc
    }
}
