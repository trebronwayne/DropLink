package io.github.trebronwayne.droplink.core

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Checks, from the phone itself, whether the server accepts connections on loopback and on each network address.
 * If loopback works but a network address fails, the problem is the network, not the server.
 */
object SelfTest {
    fun run(port: Int): List<String> {
        val lines = mutableListOf<String>()
        lines += check("127.0.0.1", port, "this phone")
        val hosts = NetworkAddresses.hosts()
        if (hosts.isEmpty()) {
            lines += "No Wi-Fi or hotspot address found. Turn on the hotspot or join a Wi-Fi network."
        }
        hosts.forEach { lines += check(it, port, "network") }
        val interfaces = NetworkAddresses.describe()
        if (interfaces.isNotEmpty()) lines += "Interfaces: " + interfaces.joinToString(", ")
        return lines
    }

    private fun check(host: String, port: Int, label: String): String {
        val started = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
            "OK    $host ($label) ${(System.nanoTime() - started) / 1_000_000} ms"
        } catch (e: Exception) {
            "FAIL  $host ($label) ${e.javaClass.simpleName}"
        }
    }
}
