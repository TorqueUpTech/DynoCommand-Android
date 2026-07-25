package com.dynocommand.app.transport

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Transparent TCP link to a dyno/handheld simulator. Carries the IDENTICAL 0xA0-framed CanPacket
 * stream as [BluetoothSppTransport] — it's just a socket instead of RFCOMM/SPP — so the same
 * framer/parser handle it unchanged. Selected when the EDM-address field holds an `IP:port`
 * (or `localhost:port`) instead of a Bluetooth MAC, letting the app run against a same-machine
 * TCP simulator with no Bluetooth adapter. (open() is called on the DynoLink watchdog thread,
 * never the main thread, so the blocking connect is safe.)
 */
class TcpTransport(private val host: String, private val port: Int) : Transport {

    override var onData: ((ByteArray) -> Unit)? = null
    override var onClosed: (() -> Unit)? = null
    override val description: String get() = "TCP $host:$port"

    private var socket: Socket? = null
    private var output: OutputStream? = null
    @Volatile private var running = false
    private var reader: Thread? = null

    override fun open(): Boolean {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = s
            output = s.getOutputStream()
            val input = s.getInputStream()
            running = true
            reader = Thread { readLoop(input) }.apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(512)
        while (running) {
            val n = try { input.read(buf) } catch (e: Exception) { -1 }
            if (n < 0) break                       // EOF / peer closed / error
            if (n > 0) onData?.invoke(buf.copyOf(n))
        }
        if (running) {                             // dropped unexpectedly (not via close())
            running = false
            onClosed?.invoke()
        }
    }

    override fun write(data: ByteArray) {
        try {
            output?.write(data)
            output?.flush()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4000

        /**
         * (host, port) if [s] is a TCP endpoint — an `IP:port` or `localhost:port` — else null (a
         * Bluetooth MAC). Requires a numeric IPv4 or "localhost" host so a colon-separated MAC
         * (e.g. 00:12:F3:04:96:2C) is never mistaken for host:port.
         */
        fun parseEndpoint(s: String?): Pair<String, Int>? {
            if (s.isNullOrBlank()) return null
            val i = s.lastIndexOf(':')
            if (i <= 0 || i == s.length - 1) return null
            val port = s.substring(i + 1).toIntOrNull() ?: return null
            if (port < 1 || port > 65535) return null
            val host = s.substring(0, i).trim()
            val isTcpHost = host.equals("localhost", ignoreCase = true) ||
                Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)
            return if (isTcpHost) Pair(host, port) else null
        }
    }
}
