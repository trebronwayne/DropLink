package io.github.trebronwayne.droplink.server

import io.github.trebronwayne.droplink.core.CrashLog
import io.github.trebronwayne.droplink.core.TlsIdentity
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * HTTPS front end. Ktor's CIO engine cannot do TLS, so Android's own TLS stack terminates HTTPS on the public
 * port and relays the decrypted bytes to the DropLink server, which listens only on this phone's loopback address.
 */
class TlsFrontend(
    private val tls: TlsIdentity.Material,
    private val publicPort: Int,
    private val backendPort: Int
) {
    private val running = AtomicBoolean(false)
    @Volatile private var listener: SSLServerSocket? = null
    private val connections = Semaphore(200)
    private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val pool = Executors.newCachedThreadPool(ThreadFactory { task ->
        Thread(task, "DropLink-tls").apply { isDaemon = true }
    })

    fun start() {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagers.init(tls.keyStore, tls.password)
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers.keyManagers, null, null)

        val server = context.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.reuseAddress = true
        val protocols = server.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (protocols.isNotEmpty()) server.enabledProtocols = protocols.toTypedArray()
        server.bind(InetSocketAddress(publicPort))
        listener = server
        running.set(true)
        pool.execute { acceptLoop(server) }
    }

    fun stop() {
        running.set(false)
        closeQuietly(listener)
        listener = null
        openSockets.toList().forEach { closeQuietly(it) }
        openSockets.clear()
        pool.shutdownNow()
    }

    private fun acceptLoop(server: SSLServerSocket) {
        while (running.get()) {
            val client: Socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running.get()) CrashLog.record("HTTPS listener stopped unexpectedly", e)
                return
            }
            if (!connections.tryAcquire()) {
                closeQuietly(client)
                continue
            }
            try {
                pool.execute {
                    try {
                        handle(client)
                    } finally {
                        connections.release()
                    }
                }
            } catch (_: Exception) {
                connections.release()
                closeQuietly(client)
            }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        openSockets.add(client)
        try {
            client.tcpNoDelay = true
            // The handshake is done here, on its own thread, so a slow client cannot block new connections.
            client.soTimeout = 10_000
            (client as SSLSocket).startHandshake()
            client.soTimeout = 0

            val backend = Socket()
            backend.tcpNoDelay = true
            backend.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), backendPort), 3_000)
            upstream = backend
            openSockets.add(backend)
            pool.execute { relay(client, backend) }
            relay(backend, client)
        } catch (_: IOException) {
            // Failed handshake (for example a browser rejecting the certificate) or a dropped connection.
        } finally {
            closeQuietly(client)
            closeQuietly(upstream)
            openSockets.remove(client)
            if (upstream != null) openSockets.remove(upstream)
        }
    }

    /** Copies bytes one way. When either direction ends, both sockets are closed. */
    private fun relay(from: Socket, to: Socket) {
        val buffer = ByteArray(128 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        } catch (_: IOException) {
            // Connection closed.
        } finally {
            closeQuietly(from)
            closeQuietly(to)
        }
    }

    private fun closeQuietly(closeable: Closeable?) {
        try { closeable?.close() } catch (_: IOException) { }
    }
}
