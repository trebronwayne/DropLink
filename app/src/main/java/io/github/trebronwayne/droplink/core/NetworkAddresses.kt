package io.github.trebronwayne.droplink.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Finds this phone's private IPv4 addresses (Wi-Fi, hotspot, USB tethering). */
object NetworkAddresses {
    /** Private addresses as ready-to-open URLs. */
    fun baseUrls(port: Int, https: Boolean = true): List<String> {
        val scheme = if (https) "https" else "http"
        return hosts().map { "$scheme://$it:$port/" }
    }

    /** Private IPv4 addresses of interfaces that are up (Wi-Fi, hotspot, tethering). */
    fun hosts(): List<String> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            Collections.list(interfaces)
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { ni ->
                    Collections.list(ni.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .mapNotNull { it.hostAddress }
                }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

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
