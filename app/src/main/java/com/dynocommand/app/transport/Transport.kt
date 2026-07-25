package com.dynocommand.app.transport

/** A byte-stream link to the dyno (real Bluetooth-SPP or the simulator). */
interface Transport {
    val description: String
    var onData: ((ByteArray) -> Unit)?
    /** Fired once when the link drops unexpectedly (not via an intentional close()). */
    var onClosed: (() -> Unit)?
    fun open(): Boolean
    fun close()
    fun write(data: ByteArray)
}
