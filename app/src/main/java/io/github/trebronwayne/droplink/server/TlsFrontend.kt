package io.github.trebronwayne.droplink.server

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.github.trebronwayne.droplink.core.CrashLog
import io.github.trebronwayne.droplink.core.TlsIdentity
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * HTTPS front end. Ktor's CIO engine cannot do TLS, so Android's own TLS stack terminates HTTPS on the public
 * port and relays the decrypted bytes to the DropLink server, which listens only on this phone's loopback address.
 *
 * Two things here exist because of what people actually do:
 *  - Someone types `192.168.1.20:8080` or `http://...` into a browser. Browsers pick plain HTTP for an address like that.
 *    A TLS server cannot answer it, so the browser just saw a closed connection ("no response"). The first bytes of every
 *    connection are therefore peeked at. Plain HTTP gets a short page that explains the browser warning and leads to the
 *    https address; real TLS carries on exactly as before.
 *  - The listener, or the certificate, can go stale while the phone changes networks. The certificate can be swapped
 *    while running, the accept loop recovers from errors, and [selfTest] lets the watchdog prove that HTTPS answers.
 */
class TlsFrontend(
    tls: TlsIdentity.Material,
    private val publicPort: Int,
    private val backendPort: Int,
    private val nodeName: String = "this phone"
) {
    private val running = AtomicBoolean(false)
    @Volatile private var identity: TlsIdentity.Material = tls
    @Volatile private var listener: SSLServerSocket? = null
    private val keys = SwappableKeyManager(managerFor(tls))
    private var context: SSLContext? = null
    private val connections = Semaphore(200)
    private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val pool = Executors.newCachedThreadPool(ThreadFactory { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            task.run()
        }, "DropLink-tls").apply { isDaemon = true }
    })

    fun start() {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(keys), null, null)
        // Let returning browsers resume sessions instead of repeating the expensive RSA handshake.
        ctx.serverSessionContext.sessionCacheSize = 1024
        ctx.serverSessionContext.sessionTimeout = 24 * 3600
        context = ctx
        listener = bind(ctx)
        running.set(true)
        pool.execute { acceptLoop() }
    }

    /** Uses a new certificate for every connection from now on, without dropping the ones in progress. */
    fun updateIdentity(tls: TlsIdentity.Material) {
        identity = tls
        keys.delegate = managerFor(tls)
    }

    fun stop() {
        running.set(false)
        closeQuietly(listener)
        listener = null
        openSockets.toList().forEach { closeQuietly(it) }
        openSockets.clear()
        pool.shutdownNow()
    }

    /** Opens a real TLS connection to this front end. A frozen or dead listener fails here even when the engine behind it is fine. */
    fun selfTest(): Boolean {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), null)
        return try {
            (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
                s.connect(InetSocketAddress("127.0.0.1", publicPort), 3_000)
                s.soTimeout = 4_000
                s.startHandshake()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun bind(ctx: SSLContext): SSLServerSocket {
        val server = ctx.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.reuseAddress = true
        server.receiveBufferSize = 512 * 1024
        val protocols = server.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (protocols.isNotEmpty()) server.enabledProtocols = protocols.toTypedArray()
        server.bind(InetSocketAddress(publicPort))
        return server
    }

    private fun acceptLoop() {
        var failures = 0
        while (running.get()) {
            val server = listener ?: return
            val client: Socket = try {
                server.accept()
            } catch (e: IOException) {
                if (!running.get()) return
                failures++
                if (failures == 1) CrashLog.record("HTTPS listener hit an error; recovering", e)
                try { Thread.sleep(if (failures < 5) 200L else 1_000L) } catch (_: InterruptedException) { return }
                // After a few errors in a row the listener is probably dead, so open a new one.
                if (failures >= 5) {
                    try {
                        closeQuietly(listener)
                        listener = context?.let { bind(it) }
                        failures = 0
                        CrashLog.record("HTTPS listener restarted", e)
                    } catch (_: Exception) { /* try again on the next round */ }
                }
                continue
            }
            failures = 0
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
            // Bigger socket buffers keep a fast Wi-Fi link full instead of stalling on the default few-KB windows.
            client.sendBufferSize = 512 * 1024

            // Look at the first bytes without taking them. Browsers also open idle connections ahead of time; those are dropped quietly.
            val first = peek(client)
            if (first != null) {
                if (first.isEmpty()) return
                if (looksLikePlainHttp(first)) {
                    answerPlainHttp(client, first)
                    return
                }
            }

            // The handshake is done here, on its own thread, so a slow client cannot block new connections.
            client.soTimeout = 10_000
            (client as SSLSocket).startHandshake()
            client.soTimeout = 0

            val backend = Socket()
            backend.tcpNoDelay = true
            backend.receiveBufferSize = 512 * 1024   // must be set before connect
            backend.sendBufferSize = 512 * 1024
            backend.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), backendPort), 3_000)
            upstream = backend
            openSockets.add(backend)
            pool.execute { relay(client, backend) }
            relay(backend, client)
        } catch (_: Exception) {
            // Failed handshake (for example a browser rejecting the certificate), a dropped connection, or the server stopping.
        } finally {
            closeQuietly(client)
            closeQuietly(upstream)
            openSockets.remove(client)
            if (upstream != null) openSockets.remove(upstream)
        }
    }

    // ---- Looking at the first bytes ----

    /**
     * Returns up to 2 KB of what the client has sent, without removing it from the socket, waiting up to 10 seconds for
     * the first byte. Empty means the client closed or sent nothing. Null means this phone cannot look ahead, in which
     * case the connection simply carries on as TLS, as it always did.
     */
    private fun peek(client: Socket): ByteArray? {
        val pfd = try { ParcelFileDescriptor.fromSocket(client) } catch (_: Throwable) { null } ?: return null
        return try {
            val fd = pfd.fileDescriptor
            val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
            if (Os.poll(arrayOf(poll), 10_000) <= 0) return ByteArray(0)
            val buffer = ByteArray(2048)
            val got = Os.recvfrom(fd, buffer, 0, buffer.size, OsConstants.MSG_PEEK, null)
            if (got <= 0) ByteArray(0) else buffer.copyOf(got)
        } catch (_: Throwable) {
            null
        } finally {
            try { pfd.close() } catch (_: Throwable) { }
        }
    }

    /** A TLS connection starts with byte 0x16. A browser asking for a page starts with a capital letter: GET, POST, HEAD... */
    private fun looksLikePlainHttp(data: ByteArray): Boolean {
        val first = data[0].toInt() and 0xFF
        if (first !in 'A'.code..'Z'.code) return false
        val start = String(data, 0, minOf(data.size, 8), Charsets.ISO_8859_1)
        return listOf("GET ", "HEAD ", "POST ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ").any { start.startsWith(it) }
    }

    // ---- Answering plain HTTP ----

    private fun answerPlainHttp(client: Socket, data: ByteArray) {
        val text = String(data, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val parts = lines.firstOrNull().orEmpty().split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = safePath(parts.getOrNull(1).orEmpty())
        fun header(name: String): String =
            lines.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
        val host = safeHost(header("Host")) ?: "${client.localAddress.hostAddress}:$publicPort"
        val target = "https://$host$path"
        val wantsPage = (method == "GET" || method == "HEAD") && header("Accept").contains("text/html", ignoreCase = true)
        val head = method == "HEAD"
        if (wantsPage) {
            val body = explainPage(target, header("User-Agent")).toByteArray(Charsets.UTF_8)
            respond(
                client, "200 OK",
                listOf(
                    "Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store",
                    "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"
                ),
                body, head
            )
        } else {
            // Scripts, downloads and other programs get a plain, temporary redirect. Temporary, so a browser never remembers it.
            respond(
                client, if (method == "GET" || method == "HEAD") "302 Found" else "307 Temporary Redirect",
                listOf("Location" to target, "Cache-Control" to "no-store"), ByteArray(0), head
            )
        }
    }

    /** Only characters that can appear in a host, so nothing a client sends can end up in a header or in the page as markup. */
    private fun safeHost(value: String): String? =
        value.takeIf { it.isNotEmpty() && it.length <= 100 && it.all { c -> c.isLetterOrDigit() || c in ".-:[]" } }

    private fun safePath(value: String): String =
        if (value.startsWith("/") && value.length <= 300 && value.all { it.code in 0x21..0x7E && it !in "\"'<>\\`" }) value else "/"

    private fun respond(client: Socket, status: String, headers: List<Pair<String, String>>, body: ByteArray, headOnly: Boolean) {
        val pfd = try { ParcelFileDescriptor.fromSocket(client) } catch (_: Throwable) { null } ?: return
        try {
            val fd = pfd.fileDescriptor
            val head = StringBuilder("HTTP/1.1 $status\r\n")
            headers.forEach { head.append(it.first).append(": ").append(it.second).append("\r\n") }
            head.append("Content-Length: ").append(body.size).append("\r\nConnection: close\r\n\r\n")
            val out = FileOutputStream(fd)
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (!headOnly && body.isNotEmpty()) out.write(body)
            out.flush()
            // Say we are finished, then read away what the browser still sends. Closing with unread data would reset the
            // connection and could throw away the page before the browser has read it.
            try { Os.shutdown(fd, OsConstants.SHUT_WR) } catch (_: Throwable) { }
            drain(fd)
        } catch (_: Exception) {
            // The browser went away; nothing more to do.
        } finally {
            try { pfd.close() } catch (_: Throwable) { }
        }
    }

    private fun drain(fd: FileDescriptor) {
        val buffer = ByteArray(4096)
        var total = 0
        try {
            repeat(8) {
                val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(poll), 800) <= 0) return
                val n = Os.read(fd, buffer, 0, buffer.size)
                if (n <= 0) return
                total += n
                if (total > 65_536) return
            }
        } catch (_: Throwable) { }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** What a person sees when they open the phone's address without https. Plain words, the steps for their browser, no jargon. */
    private fun explainPage(target: String, userAgent: String): String {
        val ua = userAgent.lowercase()
        val mine = when {
            "firefox" in ua || "fxios" in ua -> "firefox"
            "samsungbrowser" in ua -> "samsung"
            ("safari" in ua && "chrome" !in ua && "crios" !in ua && "android" !in ua) || "iphone" in ua || "ipad" in ua -> "safari"
            else -> "chrome"
        }
        fun open(id: String) = if (id == mine) " open" else ""
        val url = esc(target)
        val name = esc(nodeName)
        val fingerprint = esc(identity.fingerprint)
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DropLink: one quick step</title>
<style>
:root{color-scheme:light dark;--bg:#f4f6fb;--card:#fff;--text:#16181d;--muted:#5b6270;--accent:#2f6df6;--line:#dfe3ec}
@media(prefers-color-scheme:dark){:root{--bg:#12141a;--card:#1b1e26;--text:#eceff5;--muted:#a3abbb;--accent:#6c9bff;--line:#2d3240}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.55 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:640px;margin:0 auto;padding:24px 18px 48px}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:20px;margin:16px 0}
h1{font-size:1.5rem;line-height:1.25;margin:8px 0 6px}h2{font-size:1.05rem;margin:0 0 8px}p{margin:8px 0}
.muted{color:var(--muted);font-size:.92rem}
.btn{display:block;text-align:center;background:var(--accent);color:#fff;text-decoration:none;font-weight:650;padding:15px 18px;border-radius:14px;margin:16px 0 4px}
.btn:focus-visible{outline:3px solid var(--text);outline-offset:3px}
details{border-top:1px solid var(--line);padding:10px 0}details:first-of-type{border-top:0}
summary{cursor:pointer;font-weight:650;padding:4px 0}ol{margin:8px 0 4px;padding-left:22px}li{margin:4px 0}
code{word-break:break-all;font-size:.82rem}
</style></head><body><main>
<h1>One quick step to open DropLink</h1>
<p>$name shares files over a secure connection (https). Your browser opened the plain address, so it needs to switch.</p>
<a class="btn" href="$url">Continue to the secure page</a>
<p class="muted">Address: $url</p>
<div class="card">
<h2>Your browser will show a warning first. That is normal.</h2>
<p>DropLink makes its own security certificate on the phone, because there is no internet authority on a home network or hotspot. Browsers warn about that once. It is safe when the address is the one shown on your phone, and nobody else on the network can read your files.</p>
<details${open("chrome")}><summary>Chrome, Edge, Brave and Opera</summary><ol><li>Tap or click <b>Advanced</b>.</li><li>Tap or click <b>Proceed to ... (unsafe)</b>.</li></ol></details>
<details${open("safari")}><summary>Safari (iPhone, iPad and Mac)</summary><ol><li>Tap <b>Show Details</b>.</li><li>Tap <b>visit this website</b>, then <b>Visit Website</b>.</li></ol></details>
<details${open("firefox")}><summary>Firefox</summary><ol><li>Click <b>Advanced…</b>.</li><li>Click <b>Accept the Risk and Continue</b>.</li></ol></details>
<details${open("samsung")}><summary>Samsung Internet</summary><ol><li>Tap <b>Advanced</b>.</li><li>Tap <b>Continue to ... (unsafe)</b>.</li></ol></details>
</div>
<div class="card">
<h2>Still not opening?</h2>
<ol><li>Check this device is on the same Wi-Fi or hotspot as the phone.</li><li>Check DropLink on the phone says it is hosting.</li><li>On the phone, open DropLink, Settings, and use the option that skips the browser warning (plain http). Camera sharing and installing the app need the secure connection, but files work without it.</li></ol>
</div>
<p class="muted">To check it is really your phone, compare this fingerprint with the one in the browser's certificate details and in DropLink on the phone:<br><code>$fingerprint</code></p>
</main></body></html>"""
    }

    // ---- Relay ----

    /** Copies bytes one way. When either direction ends, both sockets are closed. */
    private fun relay(from: Socket, to: Socket) {
        val buffer = ByteArray(256 * 1024)
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

    private companion object {
        fun managerFor(tls: TlsIdentity.Material): X509ExtendedKeyManager {
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            factory.init(tls.keyStore, tls.password)
            return factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().first()
        }
    }

    /** Hands every question to the current certificate, so a renewed one takes effect without restarting the listener. */
    private class SwappableKeyManager(@Volatile var delegate: X509ExtendedKeyManager) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = delegate.getClientAliases(keyType, issuers)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = delegate.chooseClientAlias(keyType, issuers, socket)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = delegate.getServerAliases(keyType, issuers)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = delegate.chooseServerAlias(keyType, issuers, socket)
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = delegate.chooseEngineServerAlias(keyType, issuers, engine)
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = delegate.chooseEngineClientAlias(keyType, issuers, engine)
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = delegate.getCertificateChain(alias)
        override fun getPrivateKey(alias: String?): PrivateKey? = delegate.getPrivateKey(alias)
    }
}
