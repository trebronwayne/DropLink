package io.github.trebronwayne.droplink.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Finds this phone's private IPv4 addresses (Wi-Fi, hotspot, USB tethering) as ready-to-open URLs. */
object NetworkAddresses {
    fun baseUrls(port: Int): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces() ?: return emptyList())
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { ni ->
                Collections.list(ni.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { "https://${it.hostAddress}:$port/" }
            }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }
}
