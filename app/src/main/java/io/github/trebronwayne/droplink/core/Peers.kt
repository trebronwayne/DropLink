package io.github.trebronwayne.droplink.core

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Another DropLink phone this phone has paired with, so files can be sent to it directly. */
data class Peer(
    val nodeId: String,
    val name: String,
    val host: String,
    val port: Int,
    /** SHA-256 pin of the other phone's public key (or, for older versions, its certificate), set when pairing. Empty when it hosts plain HTTP. */
    val fingerprint: String,
    /** The session token the other phone gave this phone. It is sent as a cookie, exactly like a browser would. */
    val token: String,
    val createdAt: Long
)

/** What this phone knows about a paired phone that is nearby right now. */
data class PeerStatus(
    val reachable: Boolean,
    val cameras: Int = 0,
    val freeBytes: Long = -1L,
    /** What the other phone does with files from this phone: ask, always or blocked. */
    val uploadPolicy: String = ""
)

data class PeerPairing(
    val nodeId: String,
    val name: String,
    val code: String,
    val phase: Phase,
    val message: String
) {
    enum class Phase { Starting, Waiting, Done, Failed }
}

enum class PeerTransferState {
    Connecting, WaitingApproval, Sending, Done, Failed, Cancelled;

    val finished: Boolean get() = this == Done || this == Failed || this == Cancelled
}

/** A file picked on this phone. [uri] is the Android document its bytes are read from. */
data class PeerFile(val uri: String, val name: String, val size: Long, val mime: String)

data class PeerFileState(val name: String, val size: Long, val sent: Long)

/** One "send these files to that phone" action and how far it has got. */
data class PeerTransfer(
    val id: String,
    val peerId: String,
    val peerName: String,
    val createdAt: Long,
    val state: PeerTransferState,
    val files: List<PeerFileState>,
    val message: String,
    val bytesPerSecond: Long,
    val source: List<PeerFile>
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val sentBytes: Long get() = files.sumOf { it.sent }
}

private class PeerException(message: String) : Exception(message)

/**
 * Phone-to-phone sending. Every DropLink phone already runs a server with an approval-based, resumable upload API for
 * browsers. A phone that pairs with another DropLink phone simply speaks that same API: it asks to pair (the other
 * owner compares a code and approves), keeps the session token it receives, pins the other phone's certificate, and
 * then pushes files with the normal upload requests. The receiving owner keeps the same control as with a browser:
 * ask each time, always allow, or block. Interrupted sends continue from the byte where they stopped.
 */
object Peers {
    private const val PREFS = "droplink_peers"
    private const val KEY = "peers"
    private const val COOKIE = "dl_session"
    private const val BUFFER = 256 * 1024
    private const val PARALLEL_FILES = 2
    private const val MAX_TRANSFERS = 20
    private const val MAX_RETRIES = 6
    private const val APPROVAL_WAIT_MS = 10 * 60_000L
    private const val PAIR_WAIT_MS = 130_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val factories = ConcurrentHashMap<String, SSLSocketFactory>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, MutableSet<HttpURLConnection>>()

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    private var pairJob: Job? = null

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    private val _status = MutableStateFlow<Map<String, PeerStatus>>(emptyMap())
    private val _pairing = MutableStateFlow<PeerPairing?>(null)
    private val _transfers = MutableStateFlow<List<PeerTransfer>>(emptyList())

    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()
    val status: StateFlow<Map<String, PeerStatus>> = _status.asStateFlow()
    val pairing: StateFlow<PeerPairing?> = _pairing.asStateFlow()
    val transfers: StateFlow<List<PeerTransfer>> = _transfers.asStateFlow()

    fun init(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            appContext = context.applicationContext
            _peers.value = load()
        }
        // Keep each paired phone's address current as it shows up on the network, and refresh what it can do.
        scope.launch {
            Discovery.nodes.collect { nodes -> refreshAddresses(nodes) }
        }
        scope.launch {
            while (isActive) {
                refreshStatus()
                delay(20_000)
            }
        }
    }

    // ---- Pairing ----

    /** Asks the other phone to pair. Its owner sees a code and approves; this phone shows the same code. */
    fun pair(node: DiscoveredNode) {
        if (pairJob?.isActive == true) return
        pairJob = scope.launch {
            fun show(phase: PeerPairing.Phase, message: String, code: String = _pairing.value?.code.orEmpty()) {
                _pairing.value = PeerPairing(node.nodeId, node.name, code, phase, message)
            }
            show(PeerPairing.Phase.Starting, "Contacting ${node.name}…", "")
            try {
                // Pin the key when the other phone advertises it, so a renewed certificate does not break the pairing.
                val pin = node.keyPin.ifEmpty { node.fingerprint }
                val t = Target(node.host, node.port, pin)
                val body = JSONObject().put("name", Discovery.displayName()).put("model", Build.MODEL)
                    .put("platform", "DropLink for Android")
                val first = call(t, null, "/api/pair/request", "POST", body.toString())
                if (first.code == 429) {
                    show(PeerPairing.Phase.Failed, "${node.name} has too many requests waiting. Try again in a minute.")
                    return@launch
                }
                if (first.code != 200) {
                    show(PeerPairing.Phase.Failed, "${node.name} did not accept the request (${first.code}).")
                    return@launch
                }
                val json = JSONObject(first.body)
                val id = json.getString("id")
                val code = json.optString("code")
                show(PeerPairing.Phase.Waiting, "Ask the owner of ${node.name} to approve this code.", code)
                val deadline = System.currentTimeMillis() + PAIR_WAIT_MS
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(1500)
                    val r = call(t, null, "/api/pair/status?id=$id")
                    if (r.code != 200) continue
                    when (JSONObject(r.body).optString("status")) {
                        "approved" -> {
                            val token = r.cookies.firstNotNullOfOrNull { c ->
                                c.substringBefore(';').trim().takeIf { it.startsWith("$COOKIE=") }?.removePrefix("$COOKIE=")
                            }
                            if (token.isNullOrEmpty()) {
                                show(PeerPairing.Phase.Failed, "${node.name} approved, but no session came back. Try again.")
                            } else {
                                savePeer(Peer(node.nodeId, node.name, node.host, node.port, pin, token, System.currentTimeMillis()))
                                EventLog.add("Paired with ${node.name}")
                                show(PeerPairing.Phase.Done, "Paired with ${node.name}. You can now send files to it.")
                            }
                            return@launch
                        }
                        "denied" -> {
                            show(PeerPairing.Phase.Failed, "${node.name} said no.")
                            return@launch
                        }
                        "expired" -> {
                            show(PeerPairing.Phase.Failed, "The request expired. Try again.")
                            return@launch
                        }
                    }
                }
                if (isActive) show(PeerPairing.Phase.Failed, "Nobody answered on ${node.name}. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(PeerPairing.Phase.Failed, friendly(e, node.name))
            }
        }
    }

    fun cancelPairing() {
        pairJob?.cancel()
        _pairing.value = null
    }

    fun dismissPairing() {
        if (pairJob?.isActive != true) _pairing.value = null
    }

    fun unpair(nodeId: String) {
        val peer = _peers.value.firstOrNull { it.nodeId == nodeId } ?: return
        _transfers.value.filter { it.peerId == nodeId && !it.state.finished }.forEach { cancel(it.id) }
        setPeers(_peers.value.filter { it.nodeId != nodeId })
        _status.update { it - nodeId }
        EventLog.add("Unpaired from ${peer.name}")
    }

    // ---- Sending ----

    /** Starts sending [files] to the paired phone. Returns the transfer id, or null if there is nothing to do. */
    fun send(peerId: String, files: List<PeerFile>): String? {
        val peer = _peers.value.firstOrNull { it.nodeId == peerId } ?: return null
        if (files.isEmpty()) return null
        val id = hex(8)
        val transfer = PeerTransfer(
            id, peer.nodeId, peer.name, System.currentTimeMillis(), PeerTransferState.Connecting,
            files.map { PeerFileState(it.name, it.size, 0L) }, "Contacting ${peer.name}…", 0L, files
        )
        _transfers.update { (listOf(transfer) + it).take(MAX_TRANSFERS) }
        jobs[id] = scope.launch { run(id, peer, files) }
        return id
    }

    /** Stops a transfer. What already arrived stays on the other phone. */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        connections.remove(id)?.toList()?.forEach { try { it.disconnect() } catch (_: Exception) { } }
        finish(id, PeerTransferState.Cancelled, "Stopped.")
    }

    /** Tries a failed or stopped transfer again with the same files. */
    fun retry(id: String) {
        val old = _transfers.value.firstOrNull { it.id == id } ?: return
        if (!old.state.finished || old.state == PeerTransferState.Done) return
        remove(id)
        send(old.peerId, old.source)
    }

    fun remove(id: String) {
        _transfers.update { list -> list.filter { it.id != id } }
    }

    fun clearFinished() {
        _transfers.update { list -> list.filter { !it.state.finished } }
    }

    private suspend fun run(id: String, peer: Peer, files: List<PeerFile>) {
        val release = acquireLocks()
        val job = currentCoroutineContext()[Job]
        var requestId: String? = null
        var target: Target? = null
        try {
            target = targetFor(peer)
            val request = JSONObject().put(
                "files",
                JSONArray().also { a ->
                    files.forEach { f ->
                        a.put(JSONObject().put("name", f.name).put("size", f.size).put("fp", "${f.name}|${f.size}|${f.uri.hashCode()}"))
                    }
                }
            )
            val created = call(target, peer.token, "/api/upload/request", "POST", request.toString())
            when (created.code) {
                200 -> Unit
                401 -> {
                    dropUnauthorized(peer)
                    throw PeerException("${peer.name} no longer trusts this phone. Pair with it again from Servers.")
                }
                403 -> throw PeerException("${peer.name} blocks files from this phone. Its owner can allow it under Devices.")
                413 -> throw PeerException("${peer.name} cannot take that much at once.")
                429 -> throw PeerException("${peer.name} has too many requests waiting. Try again in a minute.")
                507 -> throw PeerException("${peer.name} does not have enough free space.")
                else -> throw PeerException("${peer.name} refused the files (${created.code}).")
            }
            val createdJson = JSONObject(created.body)
            val rid = createdJson.getString("id")
            requestId = rid
            var status = createdJson.optString("status")
            if (status == "pending") {
                update(id) { it.copy(state = PeerTransferState.WaitingApproval, message = "Waiting for the owner of ${peer.name} to accept…") }
                val deadline = System.currentTimeMillis() + APPROVAL_WAIT_MS
                while (status == "pending") {
                    currentCoroutineContext().ensureActive()
                    if (System.currentTimeMillis() > deadline) throw PeerException("Nobody accepted the files on ${peer.name}.")
                    delay(1000)
                    val r = call(target, peer.token, "/api/upload/status?id=$rid")
                    if (r.code == 401) {
                        dropUnauthorized(peer)
                        throw PeerException("${peer.name} no longer trusts this phone. Pair with it again from Servers.")
                    }
                    if (r.code == 200) status = JSONObject(r.body).optString("status")
                }
            }
            when (status) {
                "approved", "done" -> Unit
                "denied" -> throw PeerException("${peer.name} declined the files.")
                else -> throw PeerException("The request expired. Send again.")
            }
            update(id) { it.copy(state = PeerTransferState.Sending, message = "Sending to ${peer.name}…") }
            sendAll(id, peer, target, rid, files, job)
            finish(id, PeerTransferState.Done, "Delivered to ${peer.name}.")
        } catch (e: CancellationException) {
            finish(id, PeerTransferState.Cancelled, "Stopped.")
            val rid = requestId
            val t = target
            if (rid != null && t != null) {
                withContext(NonCancellable) {
                    try { call(t, peer.token, "/api/upload/cancel?id=$rid", "POST", "") } catch (_: Exception) { }
                }
            }
            throw e
        } catch (e: Exception) {
            finish(id, PeerTransferState.Failed, friendly(e, peer.name))
        } finally {
            jobs.remove(id)
            connections.remove(id)
            release()
        }
    }

    private class Progress(val id: String, val files: List<PeerFile>) {
        val sent = AtomicLongArray(files.size)
        @Volatile var lastAt = 0L
        @Volatile var lastBytes = 0L
        @Volatile var speed = 0L
    }

    private suspend fun sendAll(id: String, peer: Peer, t: Target, rid: String, files: List<PeerFile>, job: Job?) {
        val progress = Progress(id, files)
        val gate = Semaphore(PARALLEL_FILES)
        coroutineScope {
            files.mapIndexed { index, file ->
                async {
                    gate.withPermit { sendOne(id, peer, t, rid, index, file, progress, job) }
                }
            }.awaitAll()
        }
        publish(progress, force = true)
    }

    private suspend fun sendOne(id: String, peer: Peer, t: Target, rid: String, index: Int, file: PeerFile, progress: Progress, job: Job?) {
        var attempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val offsetReply = call(t, peer.token, "/api/upload/offset?id=$rid&index=$index")
                if (offsetReply.code == 401) {
                    dropUnauthorized(peer)
                    throw PeerException("${peer.name} no longer trusts this phone. Pair with it again from Servers.")
                }
                if (offsetReply.code == 403) throw PeerException("${peer.name} stopped accepting these files.")
                if (offsetReply.code != 200) throw IOException("The other phone answered ${offsetReply.code}.")
                val json = JSONObject(offsetReply.body)
                if (json.optBoolean("done")) {
                    progress.sent.set(index, file.size)
                    publish(progress, force = true)
                    return
                }
                val offset = json.optLong("offset", 0L).coerceIn(0L, file.size)
                progress.sent.set(index, offset)
                if (putRange(id, peer, t, rid, index, file, offset, progress, job)) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: PeerException) {
                throw e
            } catch (e: IOException) {
                if (job?.isActive == false) throw CancellationException()
                attempts++
                if (attempts > MAX_RETRIES) throw e
                update(id) { it.copy(message = "Connection dropped. Continuing from where it stopped…") }
                delay(1000L * attempts.coerceAtMost(5))
            }
        }
    }

    /** Sends the rest of one file. Returns true when it is complete, false when the offsets disagreed and should be asked again. */
    private fun putRange(
        id: String, peer: Peer, t: Target, rid: String, index: Int, file: PeerFile, offset: Long, progress: Progress, job: Job?
    ): Boolean {
        val context = appContext ?: throw PeerException("DropLink is not ready.")
        val length = file.size - offset
        val c = open(t, peer.token, "/api/upload/file?id=$rid&index=$index", "PUT")
        c.doOutput = true
        c.readTimeout = 120_000
        c.setRequestProperty("X-Upload-Offset", offset.toString())
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(length)
        val set = connections.getOrPut(id) { Collections.synchronizedSet(HashSet<HttpURLConnection>()) }
        set.add(c)
        try {
            val input: InputStream = context.contentResolver.openInputStream(Uri.parse(file.uri))
                ?: throw PeerException("Could not read ${file.name} on this phone.")
            input.use { stream ->
                skipFully(stream, offset)
                c.outputStream.use { out ->
                    val buffer = ByteArray(BUFFER)
                    var written = 0L
                    while (written < length) {
                        if (job?.isActive == false) throw CancellationException()
                        val want = minOf(buffer.size.toLong(), length - written).toInt()
                        val n = stream.read(buffer, 0, want)
                        if (n < 0) throw PeerException("${file.name} changed while it was being sent.")
                        out.write(buffer, 0, n)
                        written += n
                        progress.sent.set(index, offset + written)
                        publish(progress, force = false)
                    }
                    out.flush()
                }
            }
            val code = c.responseCode
            when (code) {
                200 -> {
                    progress.sent.set(index, file.size)
                    publish(progress, force = true)
                    return true
                }
                409 -> return false
                401 -> {
                    dropUnauthorized(peer)
                    throw PeerException("${peer.name} no longer trusts this phone. Pair with it again from Servers.")
                }
                403 -> throw PeerException("${peer.name} stopped accepting these files.")
                507 -> throw PeerException("${peer.name} ran out of space.")
                else -> throw IOException("The other phone answered $code.")
            }
        } finally {
            set.remove(c)
            try { c.disconnect() } catch (_: Exception) { }
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (input.read() < 0) throw PeerException("The file is shorter than expected.")
                left -= 1
            }
        }
    }

    private fun publish(progress: Progress, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - progress.lastAt < 250) return
        var total = 0L
        for (i in progress.files.indices) total += progress.sent.get(i)
        if (progress.lastAt != 0L && now > progress.lastAt) {
            val instant = (total - progress.lastBytes).coerceAtLeast(0L) * 1000 / (now - progress.lastAt)
            progress.speed = if (progress.speed == 0L) instant else (progress.speed * 6 + instant * 4) / 10
        }
        progress.lastAt = now
        progress.lastBytes = total
        update(progress.id) { t ->
            if (t.state.finished) t else t.copy(
                files = progress.files.mapIndexed { i, f -> PeerFileState(f.name, f.size, progress.sent.get(i)) },
                bytesPerSecond = progress.speed
            )
        }
    }

    private fun update(id: String, change: (PeerTransfer) -> PeerTransfer) {
        _transfers.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    /** Moves a transfer to its final state, once. */
    private fun finish(id: String, state: PeerTransferState, message: String) {
        update(id) { t ->
            if (t.state.finished) t else t.copy(
                state = state, message = message, bytesPerSecond = 0L,
                files = if (state == PeerTransferState.Done) t.files.map { it.copy(sent = it.size) } else t.files
            )
        }
    }

    // ---- Status ----

    private fun refreshAddresses(nodes: List<DiscoveredNode>) {
        var changed = false
        val updated = _peers.value.map { p ->
            val live = nodes.firstOrNull { it.nodeId == p.nodeId } ?: return@map p
            if (live.host != p.host || live.port != p.port || live.name != p.name) {
                changed = true
                p.copy(host = live.host, port = live.port, name = live.name.ifBlank { p.name })
            } else p
        }
        if (changed) setPeers(updated)
    }

    private suspend fun refreshStatus() {
        val nodes = Discovery.nodes.value
        val result = HashMap<String, PeerStatus>()
        for (peer in _peers.value) {
            if (nodes.none { it.nodeId == peer.nodeId }) continue
            try {
                val t = targetFor(peer)
                val info = call(t, peer.token, "/api/info")
                if (info.code == 401) {
                    dropUnauthorized(peer)
                    continue
                }
                if (info.code != 200) {
                    result[peer.nodeId] = PeerStatus(reachable = true)
                    continue
                }
                val j = JSONObject(info.body)
                val policy = try {
                    val u = call(t, peer.token, "/api/upload/info")
                    if (u.code == 200) JSONObject(u.body).optString("policy") else ""
                } catch (_: Exception) { "" }
                result[peer.nodeId] = PeerStatus(true, j.optInt("cameras", 0), j.optLong("freeBytes", -1L), policy)
            } catch (_: Exception) {
                result[peer.nodeId] = PeerStatus(reachable = false)
            }
        }
        _status.value = result
    }

    private fun dropUnauthorized(peer: Peer) {
        if (_peers.value.any { it.nodeId == peer.nodeId && it.token == peer.token }) {
            setPeers(_peers.value.filter { it.nodeId != peer.nodeId })
            EventLog.add("${peer.name} removed this phone, so it was unpaired")
        }
    }

    // ---- Network ----

    private class Target(val host: String, val port: Int, val fingerprint: String)

    private class Reply(val code: Int, val body: String, val cookies: List<String>)

    private fun targetFor(peer: Peer): Target {
        val live = Discovery.nodes.value.firstOrNull { it.nodeId == peer.nodeId }
        // Always use the fingerprint pinned when pairing. Only a phone that was plain HTTP then may adopt a new one.
        val fp = peer.fingerprint.ifEmpty { live?.let { it.keyPin.ifEmpty { it.fingerprint } } ?: "" }
        return if (live != null) Target(live.host, live.port, fp) else Target(peer.host, peer.port, fp)
    }

    private fun open(t: Target, token: String?, path: String, method: String): HttpURLConnection {
        val secure = t.fingerprint.length >= 16
        val url = URL("${if (secure) "https" else "http"}://${t.host}:${t.port}$path")
        val c = url.openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = pinned(t.fingerprint)
            // There is no certificate authority on a local network. Trust comes from the pinned fingerprint instead.
            c.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        c.requestMethod = method
        c.connectTimeout = 6_000
        c.readTimeout = 30_000
        c.useCaches = false
        c.instanceFollowRedirects = false
        if (token != null) c.setRequestProperty("Cookie", "$COOKIE=$token")
        return c
    }

    private fun call(t: Target, token: String?, path: String, method: String = "GET", body: String? = null): Reply {
        val c = open(t, token, path, method)
        try {
            if (body != null) {
                val bytes = body.toByteArray()
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Reply(code, text, c.headerFields["Set-Cookie"].orEmpty())
        } finally {
            try { c.disconnect() } catch (_: Exception) { }
        }
    }

    private fun pinned(fingerprint: String): SSLSocketFactory = factories.getOrPut(fingerprint) {
        val wanted = fingerprint.replace(":", "").lowercase()
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("The other phone sent no certificate.")
                val digest = MessageDigest.getInstance("SHA-256")
                val byKey = digest.digest(leaf.publicKey.encoded).joinToString("") { "%02x".format(it) }
                val byCertificate = digest.digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                if (wanted != byKey && wanted != byCertificate) throw CertificateException("The certificate does not match the phone you paired with.")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trust), SecureRandom())
        context.socketFactory
    }

    private fun friendly(e: Throwable, name: String): String = when (e) {
        is PeerException -> e.message.orEmpty()
        is SSLException -> "Could not confirm that this is $name. Its security certificate changed, so pair with it again."
        is ConnectException, is SocketTimeoutException, is NoRouteToHostException, is UnknownHostException ->
            "Could not reach $name. Is it hosting, and on the same Wi-Fi or hotspot?"
        is IOException -> "The connection to $name broke. ${e.message.orEmpty()}".trim()
        else -> e.message ?: "Something went wrong."
    }

    /** Keeps the phone awake and Wi-Fi at full speed while files move. Returns a function that lets go of both. */
    @Suppress("DEPRECATION")
    private fun acquireLocks(): () -> Unit {
        val context = appContext ?: return {}
        val wake = try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DropLink:peer-transfer")
                .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
        } catch (_: Throwable) { null }
        val wifi = try {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            (context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "DropLink:peer-transfer")
                .apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) { null }
        return {
            try { if (wake?.isHeld == true) wake.release() } catch (_: Throwable) { }
            try { if (wifi?.isHeld == true) wifi.release() } catch (_: Throwable) { }
        }
    }

    // ---- Storage ----

    private fun savePeer(peer: Peer) {
        setPeers(_peers.value.filter { it.nodeId != peer.nodeId } + peer)
    }

    private fun setPeers(list: List<Peer>) {
        _peers.value = list
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject().put("id", it.nodeId).put("name", it.name).put("host", it.host).put("port", it.port)
                    .put("fp", it.fingerprint).put("token", it.token).put("created", it.createdAt)
            )
        }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, array.toString())?.apply()
    }

    private fun load(): List<Peer> {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return emptyList()
        return try {
            val array = JSONArray(prefs.getString(KEY, "[]"))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Peer(o.getString("id"), o.getString("name"), o.optString("host"), o.optInt("port", 8080), o.optString("fp"), o.getString("token"), o.optLong("created"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
