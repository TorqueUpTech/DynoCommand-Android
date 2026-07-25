package com.dynocommand.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Classic Bluetooth SPP/RFCOMM link to the EDM — the same role the Windows
 * COM-port path plays. Standard SPP service UUID.
 *
 * Connects without requiring a prior bond: it tries an *insecure* (unauthenticated)
 * RFCOMM channel first, which most SPP modules accept by MAC with no pairing, and
 * falls back to a secure channel (which may trigger pairing) only if that fails.
 */
class BluetoothSppTransport(private val device: BluetoothDevice) : Transport {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    override var onData: ((ByteArray) -> Unit)? = null
    override var onClosed: (() -> Unit)? = null
    override val description: String get() = "BT ${device.address}"

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    @Volatile private var running = false
    private var reader: Thread? = null

    @SuppressLint("MissingPermission") // caller ensures BLUETOOTH_CONNECT is granted
    override fun open(): Boolean {
        // Discovery, if ever running, slows/blocks a connect — cancel it best-effort.
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}
        // Insecure first (no bonding); secure fallback only if the device demands it.
        return tryConnect(insecure = true) || tryConnect(insecure = false)
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(insecure: Boolean): Boolean {
        return try {
            val s = if (insecure)
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            else
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = s
            s.connect()
            output = s.outputStream
            val input = s.inputStream
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
}
