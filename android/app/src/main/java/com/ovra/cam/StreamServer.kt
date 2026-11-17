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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TCP server the PC connects to (directly over Wi-Fi, or via `adb forward` over USB).
 * Phone -> PC: [type:1][len:4 BE][payload]
 *   type 2 = status JSON
 *   type 3 = H.264: [rotation:2 BE][flags:1 (1=front, 2=key, 4=config)][Annex B]
 * PC -> phone: one JSON command per line.
 */
class StreamServer(
    private val port: Int,
    private val onCommand: (JSONObject) -> Unit,
    private val onClientChanged: (connected: Boolean, address: String?) -> Unit,
    private val onNeedKeyframe: () -> Unit,
) {
    private class Packet(val data: ByteArray, val rotation: Int, val flags: Int)

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private val queue = ArrayBlockingQueue<Packet>(150)
    @Volatile private var waitingForKey = true
    private val latestStatus = AtomicReference<String?>(null)

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
    }

    /** Queue encoded video. Config packets are always sent; frames wait for a keyframe after any drop. */
    fun sendVideo(data: ByteArray, rotation: Int, flags: Int) {
        if (client == null) return
        val isConfig = flags and 4 != 0
        val isKey = flags and 2 != 0
        if (!isConfig && waitingForKey) {
            if (!isKey) return
            waitingForKey = false
        }
        if (!queue.offer(Packet(data, rotation, flags))) {
            // network too slow: drop everything and restart from the next keyframe
            queue.clear()
            waitingForKey = true
            onNeedKeyframe()
        }
    }

    /** Sends codec config first to a newly connected PC. */
    fun primeWithConfig(config: ByteArray?, rotation: Int, flags: Int) {
        queue.clear()
        waitingForKey = true
        if (config != null) queue.offer(Packet(config, rotation, flags or 4))
        onNeedKeyframe()
    }

    fun sendStatus(json: JSONObject) {
        latestStatus.set(json.toString())
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
                    s.sendBufferSize = 2 * 1024 * 1024
                    // newest PC wins
                    try { client?.close() } catch (_: Exception) {}
                    queue.clear()
                    waitingForKey = true
                    client = s
                    onClientChanged(true, s.inetAddress?.hostAddress)
                    Thread({ readLoop(s) }, "ovra-read").start()
                    Thread({ writeLoop(s) }, "ovra-write").start()
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "server error: ${e.message}")
                    try { Thread.sleep(1000) } catch (_: InterruptedException) {}
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
            queue.clear()
            onClientChanged(false, null)
        }
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
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 512 * 1024))
            while (running && client === s) {
                val status = latestStatus.getAndSet(null)
                if (status != null) {
                    val bytes = status.toByteArray(Charsets.UTF_8)
                    out.writeByte(2)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
                val p = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.writeByte(3)
                out.writeInt(p.data.size + 3)
                out.writeShort(p.rotation)
                out.writeByte(p.flags)
                out.write(p.data)
                if (queue.isEmpty()) out.flush()
            }
        } catch (_: Exception) {
        }
        dropClient(s)
    }

    companion object { private const val TAG = "OvraStream" }
}
