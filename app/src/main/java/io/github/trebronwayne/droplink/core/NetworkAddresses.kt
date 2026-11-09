package io.github.trebronwayne.droplink.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

enum class NetKind(val label: String, val rank: Int, val reachable: Boolean) {
    Hotspot("Hotspot", 0, true),
    Usb("USB tethering", 1, true),
    Wifi("Wi-Fi network", 2, true),
    Ethernet("Ethernet", 3, true),
    Other("Network", 4, true),
    MobileData("Mobile data", 9, false)
}

/** One address another device could try. */
data class HostAddress(val host: String, val kind: NetKind, val iface: String) {
    fun url(port: Int, https: Boolean) = "${if (https) "https" else "http"}://$host:$port/"
}

/**
 * Finds this phone's private IPv4 addresses and says what each one is.
 * Mobile data usually also gets a private address, but nothing on a hotspot or Wi-Fi can reach it, so it is
 * recognised and left out; only addresses other devices can really use are offered (and turned into QR codes).
 */
object NetworkAddresses {
    // Typical default hotspot gateways, used when the interface name alone is not telling.
    private val HOTSPOT_HOSTS = setOf("192.168.43.1", "192.168.49.1", "172.20.10.1", "192.168.137.1")

    private fun kindOf(name: String, host: String): NetKind {
        val n = name.lowercase()
        return when {
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("v4-") ||
                n.startsWith("ppp") || n.startsWith("wwan") || n.startsWith("seth") || n.startsWith("qmi") -> NetKind.MobileData
            n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") || n.startsWith("p2p") ||
                n == "wlan1" || n == "wlan2" || host in HOTSPOT_HOSTS -> NetKind.Hotspot
            n.startsWith("rndis") || n.startsWith("usb") -> NetKind.Usb
            n.startsWith("wlan") -> NetKind.Wifi
            n.startsWith("eth") -> NetKind.Ethernet
            else -> NetKind.Other
        }
    }

    /** Every private address with its type, best first, mobile data included (and marked) for troubleshooting. */
    fun classified(): List<HostAddress> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            Collections.list(interfaces)
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { ni ->
                    Collections.list(ni.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .mapNotNull { a -> a.hostAddress?.let { HostAddress(it, kindOf(ni.name, it), ni.name) } }
                }
                .distinctBy { it.host }
                .sortedBy { it.kind.rank }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Addresses other devices can actually reach (no mobile data), best first. */
    fun reachable(): List<HostAddress> = classified().filter { it.kind.reachable }

    /** Reachable addresses as ready-to-open URLs. */
    fun baseUrls(port: Int, https: Boolean = true): List<String> = reachable().map { it.url(port, https) }

    fun hosts(): List<String> = reachable().map { it.host }

    /** Every active IPv4 interface with its name, for troubleshooting (for example "ap0 192.168.43.1"). */
    fun describe(): List<String> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            Collections.list(interfaces)
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni ->
                    Collections.list(ni.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { address -> address.hostAddress?.let { "${ni.name} $it" } }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
