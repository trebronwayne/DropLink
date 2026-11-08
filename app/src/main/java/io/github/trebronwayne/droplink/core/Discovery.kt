package io.github.trebronwayne.droplink.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address

data class DiscoveredNode(
    val serviceName: String,
    val name: String,
    val nodeId: String,
    val host: String,
    val port: Int,
    val fingerprint: String
)

/** LAN discovery with Android NSD (mDNS/DNS-SD): advertises this node while hosting and lists other nodes. */
object Discovery {
    private const val SERVICE_TYPE = "_droplink._tcp."
    private val lock = Any()
    private val _nodes = MutableStateFlow<List<DiscoveredNode>>(emptyList())
    val nodes: StateFlow<List<DiscoveredNode>> = _nodes.asStateFlow()

    private var manager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun displayName(): String =
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()

    fun startBrowsing(context: Context) {
        synchronized(lock) {
            if (discoveryListener != null) return
            val nsd = context.applicationContext.getSystemService(NsdManager::class.java) ?: return
            manager = nsd
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    synchronized(lock) { discoveryListener = null }
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) = enqueue(serviceInfo)
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    _nodes.update { list -> list.filterNot { it.serviceName == serviceInfo.serviceName } }
                }
            }
            discoveryListener = listener
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Exception) {
                discoveryListener = null
            }
        }
    }

    fun stopBrowsing() {
        synchronized(lock) {
            discoveryListener?.let { try { manager?.stopServiceDiscovery(it) } catch (_: Exception) { } }
            discoveryListener = null
            queue.clear()
            resolving = false
            _nodes.value = emptyList()
        }
    }

    fun advertise(context: Context, nodeId: String, fingerprint: String, name: String, port: Int) {
        synchronized(lock) {
            if (registrationListener != null) return
            val nsd = context.applicationContext.getSystemService(NsdManager::class.java) ?: return
            manager = nsd
            val info = NsdServiceInfo().apply {
                serviceName = "DropLink-${nodeId.takeLast(8)}"
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("id", nodeId)
                setAttribute("fp", fingerprint.replace(":", ""))
                setAttribute("name", name)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { registrationListener = null }
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            }
            registrationListener = listener
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Exception) {
                registrationListener = null
            }
        }
    }

    fun stopAdvertising() {
        synchronized(lock) {
            registrationListener?.let { try { manager?.unregisterService(it) } catch (_: Exception) { } }
            registrationListener = null
        }
    }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(lock) {
            queue.addLast(info)
            if (!resolving) resolveNext()
        }
    }

    // Only one resolve may run at a time on many Android versions, so requests are queued.
    private fun resolveNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            resolving = false
            return
        }
        resolving = true
        try {
            manager?.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { resolveNext() }
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handleResolved(serviceInfo)
                    synchronized(lock) { resolveNext() }
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val host = info.host as? Inet4Address ?: return
        fun attr(key: String) = info.attributes[key]?.toString(Charsets.UTF_8).orEmpty()
        val nodeId = attr("id")
        if (nodeId.isEmpty()) return
        val node = DiscoveredNode(
            serviceName = info.serviceName,
            name = attr("name").ifEmpty { info.serviceName },
            nodeId = nodeId,
            host = host.hostAddress ?: return,
            port = info.port,
            fingerprint = attr("fp")
        )
        _nodes.update { list -> list.filterNot { it.serviceName == node.serviceName } + node }
    }
}
