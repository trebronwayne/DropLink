package io.github.trebronwayne.droplink.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

enum class CameraRequestStatus { Pending, Denied }

/** A paired device asking to act as a Camera Node. Approval is given inside the app, like pairing. */
data class CameraRequest(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Long,
    val status: CameraRequestStatus = CameraRequestStatus.Pending,
    val updatedAt: Long = createdAt
)

/** One picture a node offers (for example the front or the back camera). [id] is the id of its media stream. */
data class CameraFeed(val id: String, val label: String)

/**
 * A browser sharing its camera and microphone. The phone only keeps this card (who, what, is it still there); the
 * pictures and sound go straight from the node to whoever watches, never through the phone.
 */
data class CameraNode(
    val addr: String,
    val deviceId: String,
    val clientId: String,
    val name: String,
    val model: String,
    val feeds: List<CameraFeed>,
    val mic: Boolean,
    val viewers: Int,
    val startedAt: Long,
    val lastSeen: Long,
    /** False once the node stopped on purpose. A node that just vanished is offline after [CameraHub.NODE_ONLINE_MS]. */
    val live: Boolean = true
) {
    fun isOnline(now: Long = System.currentTimeMillis()) = live && now - lastSeen <= CameraHub.NODE_ONLINE_MS
}

sealed interface CameraStartResult {
    class Approved(val node: CameraNode) : CameraStartResult
    class Pending(val request: CameraRequest) : CameraStartResult
    object Denied : CameraStartResult
    object Blocked : CameraStartResult
    object TooMany : CameraStartResult
    class Invalid(val reason: String) : CameraStartResult
}

/** One set-up message waiting in a mailbox. [from] is stamped by the phone, so a sender cannot pretend to be someone else. */
class CameraMessage(val n: Long, val from: String, val session: String, val type: String, val data: String)

sealed interface CameraSendResult {
    object Sent : CameraSendResult
    object NoTarget : CameraSendResult
    object TargetOffline : CameraSendResult
    object TooFast : CameraSendResult
    class Invalid(val reason: String) : CameraSendResult
}

/**
 * Camera Nodes. A paired browser asks to become a node; the phone's owner allows it once, always, or not at all.
 * Watching browsers then find the node here and set up a direct WebRTC connection with it. The phone only carries
 * the small set-up messages (offer, answer, ICE) in mailboxes that browsers read with long polling, which is why
 * it stays light and needs no camera or microphone permission of its own.
 */
object CameraHub {
    const val NODE_ONLINE_MS = 35_000L
    const val MAX_NODES = 8
    const val MAX_NODES_PER_DEVICE = 2
    const val MAX_MESSAGE_CHARS = 24_000

    private const val FORGET_MS = 24 * 60 * 60_000L
    private const val GRANT_MS = 3 * 60_000L
    private const val PENDING_TTL_MS = 2 * 60_000L
    private const val DENIED_TTL_MS = 60_000L
    private const val VIEW_BOX_IDLE_MS = 60_000L
    private const val MAX_VIEW_BOXES_PER_DEVICE = 12
    private const val MAX_QUEUE = 300
    private const val MAX_PER_SECOND = 120
    private const val MAX_REQUESTS_PER_MINUTE = 5

    private val ID = Regex("^[A-Za-z0-9_-]{6,40}$")
    private val ADDR = Regex("^(node|view):[0-9a-f]{16}:[A-Za-z0-9_-]{6,40}$")
    private val TYPES = setOf("join", "offer", "answer", "ice", "bye", "busy")

    private class Mailbox(val deviceId: String) {
        val lock = Any()
        val messages = ArrayList<CameraMessage>()
        val sent = ArrayDeque<Long>()
        var seq = 0L
        val wake = Channel<Unit>(Channel.CONFLATED)
        @Volatile var lastPoll = System.currentTimeMillis()

        fun push(from: String, session: String, type: String, data: String): Boolean {
            synchronized(lock) {
                if (messages.size >= MAX_QUEUE) return false
                messages += CameraMessage(++seq, from, session, type, data)
            }
            wake.trySend(Unit)
            return true
        }

        fun take(since: Long): Pair<List<CameraMessage>, Long> = synchronized(lock) {
            messages.removeAll { it.n <= since }
            ArrayList(messages) to seq
        }

        fun allowSend(now: Long): Boolean = synchronized(lock) {
            while (sent.isNotEmpty() && now - sent.first() > 1_000) sent.removeFirst()
            if (sent.size >= MAX_PER_SECOND) false else { sent.addLast(now); true }
        }
    }

    private val lock = Any()
    private val random = SecureRandom()
    private val nodes = LinkedHashMap<String, CameraNode>()
    private val grants = HashMap<String, Long>()
    private val mailboxes = ConcurrentHashMap<String, Mailbox>()
    private val recentRequests = HashMap<String, ArrayDeque<Long>>()
    private val _requests = MutableStateFlow<List<CameraRequest>>(emptyList())
    private val _nodes = MutableStateFlow<List<CameraNode>>(emptyList())
    @Volatile private var lastPublish = 0L

    /** Requests the phone's owner can still answer. */
    val requests: StateFlow<List<CameraRequest>> = _requests.asStateFlow()

    /** Every node the phone knows about, newest first. Use [CameraNode.isOnline] to tell who is there right now. */
    val nodeState: StateFlow<List<CameraNode>> = _nodes.asStateFlow()

    /** Called when a node needs an answer, so the service can raise a notification. */
    @Volatile var onRequest: ((CameraRequest) -> Unit)? = null

    fun addr(role: String, deviceId: String, clientId: String) = "$role:$deviceId:$clientId"
    fun validClientId(id: String) = ID.matches(id)

    /* ------------------------------------------------ starting a node ------------------------------------------------ */

    /**
     * A browser wants to be a Camera Node. Safe to call again and again: the browser repeats it every couple of seconds
     * while it waits for the phone's owner to answer.
     */
    fun start(device: TrustedDevice, clientId: String, rawName: String, feeds: List<CameraFeed>, mic: Boolean): CameraStartResult {
        if (!ID.matches(clientId)) return CameraStartResult.Invalid("bad_client")
        var created: CameraRequest? = null
        val result: CameraStartResult
        synchronized(lock) {
            val now = System.currentTimeMillis()
            purge(now)
            val fresh = Pairing.trusted.value.firstOrNull { it.id == device.id } ?: return CameraStartResult.Blocked
            if (fresh.camera == CameraPolicy.Blocked) return CameraStartResult.Blocked
            val granted = fresh.camera == CameraPolicy.Always || (grants[device.id] ?: 0L) > now
            if (granted) {
                val addr = addr("node", device.id, clientId)
                val isNew = !nodes.containsKey(addr)
                if (isNew) {
                    if (nodes.values.count { it.live && it.isOnline(now) } >= MAX_NODES) return CameraStartResult.TooMany
                    if (nodes.values.count { it.deviceId == device.id && it.live && it.isOnline(now) } >= MAX_NODES_PER_DEVICE) return CameraStartResult.TooMany
                }
                _requests.value = _requests.value.filter { it.deviceId != device.id }
                grants[device.id] = now + GRANT_MS
                val old = nodes[addr]
                val node = CameraNode(
                    addr, device.id, clientId, cleanName(rawName).ifEmpty { fresh.label }, fresh.model,
                    cleanFeeds(feeds), mic, 0, if (old?.live == true) old.startedAt else now, now
                )
                nodes[addr] = node
                mailboxes.getOrPut(addr) { Mailbox(device.id) }.lastPoll = now
                publish(force = true)
                return CameraStartResult.Approved(node)
            }
            val existing = _requests.value.firstOrNull { it.deviceId == device.id }
            if (existing != null) {
                return if (existing.status == CameraRequestStatus.Denied) CameraStartResult.Denied else CameraStartResult.Pending(existing)
            }
            val times = recentRequests.getOrPut(device.id) { ArrayDeque() }
            while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
            if (times.size >= MAX_REQUESTS_PER_MINUTE) return CameraStartResult.TooMany
            times.addLast(now)
            val request = CameraRequest(hex(12), device.id, fresh.label, now)
            _requests.value = _requests.value + request
            created = request
            result = CameraStartResult.Pending(request)
        }
        created?.let {
            EventLog.add("${it.deviceName} asked to be a Camera Node")
            onRequest?.invoke(it)
        }
        return result
    }

    /** The owner allows a waiting request. [always] also stops asking next time for this device. */
    fun approve(id: String, always: Boolean) {
        var deviceId: String? = null
        synchronized(lock) {
            val request = _requests.value.firstOrNull { it.id == id && it.status == CameraRequestStatus.Pending } ?: return
            deviceId = request.deviceId
            grants[request.deviceId] = System.currentTimeMillis() + GRANT_MS
        }
        deviceId?.let { if (always) Pairing.setCameraPolicy(it, CameraPolicy.Always) }
        EventLog.add("Camera Node allowed" + if (always) " (always)" else "")
    }

    fun deny(id: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _requests.value = _requests.value.map {
                if (it.id == id && it.status == CameraRequestStatus.Pending) it.copy(status = CameraRequestStatus.Denied, updatedAt = now) else it
            }
        }
    }

    /** Changes what this device may do and settles whatever was waiting on it. */
    fun setPolicy(deviceId: String, policy: CameraPolicy) {
        Pairing.setCameraPolicy(deviceId, policy)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            when (policy) {
                CameraPolicy.Blocked -> {
                    grants.remove(deviceId)
                    _requests.value = _requests.value.filter { it.deviceId != deviceId }
                    stopAllOf(deviceId, now)
                }
                CameraPolicy.Always -> grants[deviceId] = now + GRANT_MS
                CameraPolicy.Ask -> Unit
            }
            publish(force = true)
        }
    }

    /** A device was revoked: its nodes and requests go with it. */
    fun forget(deviceId: String) {
        synchronized(lock) {
            grants.remove(deviceId)
            _requests.value = _requests.value.filter { it.deviceId != deviceId }
            stopAllOf(deviceId, System.currentTimeMillis())
            mailboxes.entries.removeAll { it.value.deviceId == deviceId }
            publish(force = true)
        }
    }

    /** The node's owner stopped it. It stays listed as offline for a day, so watchers know it exists. */
    fun stop(device: TrustedDevice, clientId: String) {
        synchronized(lock) {
            val addr = addr("node", device.id, clientId)
            nodes[addr]?.let { nodes[addr] = it.copy(live = false, viewers = 0) }
            mailboxes.remove(addr)
            if (nodes.values.none { it.deviceId == device.id && it.live }) grants.remove(device.id)
            publish(force = true)
        }
    }

    /** The node tells the phone what it shows now (cameras, microphone, how many are watching). */
    fun update(device: TrustedDevice, clientId: String, rawName: String?, feeds: List<CameraFeed>?, mic: Boolean?, viewers: Int?): Boolean =
        synchronized(lock) {
            val addr = addr("node", device.id, clientId)
            val node = nodes[addr]?.takeIf { it.live } ?: return@synchronized false
            nodes[addr] = node.copy(
                name = rawName?.let { cleanName(it) }?.ifEmpty { node.name } ?: node.name,
                feeds = feeds?.let { cleanFeeds(it) } ?: node.feeds,
                mic = mic ?: node.mic,
                viewers = viewers?.coerceIn(0, 99) ?: node.viewers,
                lastSeen = System.currentTimeMillis()
            )
            publish(force = true)
            true
        }

    /** What watchers see: online nodes first. */
    fun list(now: Long = System.currentTimeMillis()): List<CameraNode> = synchronized(lock) {
        purge(now)
        nodes.values.sortedWith(compareByDescending<CameraNode> { it.isOnline(now) }.thenByDescending { it.lastSeen })
    }

    fun find(addr: String): CameraNode? = synchronized(lock) { nodes[addr] }

    /* ------------------------------------------------ signalling ------------------------------------------------ */

    /** Waits up to [waitMs] for messages after [since]. Returns null if this mailbox does not exist (node not started). */
    suspend fun poll(device: TrustedDevice, role: String, clientId: String, since: Long, waitMs: Long): Pair<List<CameraMessage>, Long>? {
        if (!ID.matches(clientId) || (role != "node" && role != "view")) return null
        val address = addr(role, device.id, clientId)
        val box: Mailbox? = if (role == "node") {
            synchronized(lock) { if (nodes[address]?.live == true) mailboxes[address] else null }
        } else {
            synchronized(lock) {
                purge(System.currentTimeMillis())
                val existing = mailboxes[address]
                if (existing != null) existing
                else if (mailboxes.entries.count { it.key.startsWith("view:") && it.value.deviceId == device.id } >= MAX_VIEW_BOXES_PER_DEVICE) null
                else Mailbox(device.id).also { mailboxes[address] = it }
            }
        }
        if (box == null) return null
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            val now = System.currentTimeMillis()
            box.lastPoll = now
            if (role == "node") markSeen(address, now)
            val got = box.take(since)
            if (got.first.isNotEmpty()) return got
            val left = deadline - now
            if (left <= 0) return got
            withTimeoutOrNull(left) { box.wake.receive() }
        }
    }

    /** Hands one set-up message to another browser's mailbox. The phone stamps who it is from. */
    fun send(device: TrustedDevice, role: String, clientId: String, to: String, session: String, type: String, data: String): CameraSendResult {
        if (!ID.matches(clientId) || (role != "node" && role != "view")) return CameraSendResult.Invalid("bad_client")
        if (!ID.matches(session)) return CameraSendResult.Invalid("bad_session")
        if (type !in TYPES) return CameraSendResult.Invalid("bad_type")
        if (data.length > MAX_MESSAGE_CHARS) return CameraSendResult.Invalid("too_large")
        if (!ADDR.matches(to) || !to.startsWith(if (role == "view") "node:" else "view:")) return CameraSendResult.Invalid("bad_target")
        val from = addr(role, device.id, clientId)
        val now = System.currentTimeMillis()
        val mine = mailboxes[from] ?: return CameraSendResult.Invalid("not_started")
        if (!mine.allowSend(now)) return CameraSendResult.TooFast
        val target = mailboxes[to] ?: return CameraSendResult.NoTarget
        if (role == "view") {
            val node = synchronized(lock) { nodes[to] }
            if (node == null || !node.isOnline(now)) return CameraSendResult.TargetOffline
        }
        return if (target.push(from, session, type, data)) CameraSendResult.Sent else CameraSendResult.TooFast
    }

    /* ------------------------------------------------ housekeeping ------------------------------------------------ */

    fun clearAll() {
        synchronized(lock) {
            nodes.clear(); grants.clear(); mailboxes.clear(); recentRequests.clear()
            _requests.value = emptyList()
            publish(force = true)
        }
    }

    private fun markSeen(addr: String, now: Long) {
        synchronized(lock) {
            val node = nodes[addr] ?: return
            if (!node.live) return
            nodes[addr] = node.copy(lastSeen = now)
            // A node that is still there keeps its "allow this time" alive, so a reload does not ask again.
            if (grants.containsKey(node.deviceId)) grants[node.deviceId] = now + GRANT_MS
            publish(force = false)
        }
    }

    private fun stopAllOf(deviceId: String, now: Long) {
        nodes.entries.filter { it.value.deviceId == deviceId }.forEach { (addr, node) ->
            nodes[addr] = node.copy(live = false, viewers = 0, lastSeen = now)
            mailboxes.remove(addr)
        }
    }

    private fun purge(now: Long) {
        _requests.value = _requests.value.filter {
            val limit = if (it.status == CameraRequestStatus.Pending) PENDING_TTL_MS else DENIED_TTL_MS
            now - it.updatedAt < limit
        }
        grants.entries.removeAll { it.value <= now }
        nodes.entries.removeAll { (addr, node) ->
            val gone = now - node.lastSeen > FORGET_MS
            if (gone) mailboxes.remove(addr)
            gone
        }
        mailboxes.entries.removeAll { (addr, box) -> addr.startsWith("view:") && now - box.lastPoll > VIEW_BOX_IDLE_MS }
    }

    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 4_000) return
        lastPublish = now
        _nodes.value = nodes.values.sortedByDescending { it.lastSeen }
    }

    private fun cleanName(raw: String) = raw.filter { !it.isISOControl() }.trim().take(40)

    private fun cleanFeeds(feeds: List<CameraFeed>): List<CameraFeed> =
        feeds.take(2).mapNotNull {
            val id = it.id.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' || c == '{' || c == '}' }.take(80)
            if (id.isEmpty()) null else CameraFeed(id, it.label.filter { c -> !c.isISOControl() }.trim().take(24).ifEmpty { "Camera" })
        }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
