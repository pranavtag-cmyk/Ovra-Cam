package com.ovra.cam

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Announces the phone on the local network so the PC app can list it ("OVRACAM|1|name|port" to UDP 5151). */
class Beacon(private val name: String, private val tcpPort: Int) {
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({ loop() }, "ovra-beacon").start()
    }

    fun stop() { running = false }

    private fun loop() {
        val msg = "OVRACAM|1|${name.replace('|', ' ')}|$tcpPort".toByteArray(Charsets.UTF_8)
        var socket: DatagramSocket? = null
        while (running) {
            try {
                if (socket == null) socket = DatagramSocket().apply { broadcast = true }
                val targets = mutableSetOf<InetAddress>()
                targets.add(InetAddress.getByName("255.255.255.255"))
                targets.addAll(broadcastAddresses())
                for (t in targets) {
                    try { socket.send(DatagramPacket(msg, msg.size, t, PORT)) } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        }
        try { socket?.close() } catch (_: Exception) {}
    }

    companion object {
        const val PORT = 5151

        fun broadcastAddresses(): List<InetAddress> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        } catch (_: Exception) { emptyList() }

        /** Local IPv4 addresses, Wi-Fi first, for showing to the user. */
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("dummy") }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
    }
}
