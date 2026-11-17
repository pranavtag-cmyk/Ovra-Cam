package com.ovra.cam

import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * TCP server the PC connects to (directly over Wi-Fi, or via `adb forward` over USB).
 * Phone -> PC: [type:1][len:4 BE][payload]   type 1 = frame ([rotation:2 BE][flags:1][jpeg]), type 2 = status JSON
 * PC -> phone: one JSON command per line.
 */
class StreamServer(
    private val port: Int,
    private val onCommand: (JSONObject) -> Unit,
    private val onClientChanged: (connected: Boolean, address: String?) -> Unit,
) {
    private class Frame(val jpeg: ByteArray, val rotation: Int, val flags: Int)

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private val latestFrame = AtomicReference<Frame?>(null)
    private val latestStatus = AtomicReference<String?>(null)
    private val signal = Object()

    val hasClient: Boolean get() = client != null

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "ovra-accept").start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        synchronized(signal) { signal.notifyAll() }
    }

    fun sendFrame(jpeg: ByteArray, rotation: Int, flags: Int) {
        latestFrame.set(Frame(jpeg, rotation, flags))
        synchronized(signal) { signal.notifyAll() }
    }

    fun sendStatus(json: JSONObject) {
        latestStatus.set(json.toString())
        synchronized(signal) { signal.notifyAll() }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                while (running) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    s.sendBufferSize = 1024 * 1024
                    // newest PC wins
                    try { client?.close() } catch (_: Exception) {}
                    client = s
                    latestFrame.set(null)
                    onClientChanged(true, s.inetAddress?.hostAddress)
                    Thread({ readLoop(s) }, "ovra-read").start()
                    Thread({ writeLoop(s) }, "ovra-write").start()
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "server error: ${e.message}")
                    Thread.sleep(1000)
                }
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun dropClient(s: Socket) {
        try { s.close() } catch (_: Exception) {}
        if (client === s) {
            client = null
            onClientChanged(false, null)
        }
        synchronized(signal) { signal.notifyAll() }
    }

    private fun readLoop(s: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (running && client === s) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try { onCommand(JSONObject(line)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
        dropClient(s)
    }

    private fun writeLoop(s: Socket) {
        try {
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
            while (running && client === s) {
                val status = latestStatus.getAndSet(null)
                val frame = latestFrame.getAndSet(null)
                if (status == null && frame == null) {
                    synchronized(signal) { signal.wait(500) }
                    continue
                }
                if (status != null) {
                    val bytes = status.toByteArray(Charsets.UTF_8)
                    out.writeByte(2)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
                if (frame != null) {
                    out.writeByte(1)
                    out.writeInt(frame.jpeg.size + 3)
                    out.writeShort(frame.rotation)
                    out.writeByte(frame.flags)
                    out.write(frame.jpeg)
                }
                out.flush()
            }
        } catch (_: Exception) {
        }
        dropClient(s)
    }

    companion object { private const val TAG = "OvraStream" }
}
