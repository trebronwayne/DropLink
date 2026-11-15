package io.github.trebronwayne.droplink.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

enum class UploadStatus { Pending, Approved, Denied, Expired, Done }

/**
 * One file a device wants to send. The name is already cleaned; the size is what the device announced.
 * [fingerprint] identifies the file (name, size, date) so a half-received copy can be continued later.
 */
data class UploadFileSpec(val name: String, val size: Long, val fingerprint: String = "")

/** Where one incoming file stands. */
enum class ReceiveState { Waiting, Receiving, Paused, Done }

/** [bytes] already stored on the phone for that file, and what is happening with it. */
data class ReceiveProgress(val bytes: Long, val state: ReceiveState)

/**
 * A batch of files one paired device wants to send. It stays [UploadStatus.Pending] until the phone's owner answers,
 * unless that device is set to "always allow". Approval covers exactly these files and expires, so an old approval
 * can never be reused for something else.
 */
data class UploadRequest(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val files: List<UploadFileSpec>,
    val createdAt: Long,
    val status: UploadStatus,
    val updatedAt: Long = createdAt,
    /** True when this request was approved because the device is on "always allow". */
    val always: Boolean = false
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

/** The outcome of asking to send files. */
sealed interface UploadCreateResult {
    class Created(val request: UploadRequest) : UploadCreateResult
    object Blocked : UploadCreateResult
    object TooMany : UploadCreateResult
    class Invalid(val reason: String) : UploadCreateResult
}

/** One file of an approved request that a device may now send. [generation] lets a newer connection take over. */
class UploadSlot(val request: UploadRequest, val index: Int, val spec: UploadFileSpec, val generation: Int)

/**
 * Receiving files. A paired device describes the files it wants to send, the phone's owner approves once, approves
 * always (for that device) or denies, and only then does the phone accept the bytes. Rate limits keep a device from
 * flooding the screen with requests.
 */
object Uploads {
    const val MAX_FILES = 50
    const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024

    private const val PENDING_TTL_MS = 5 * 60_000L
    private const val APPROVED_TTL_MS = 30 * 60_000L
    private const val FINISHED_TTL_MS = 2 * 60_000L
    private const val MAX_PENDING_PER_DEVICE = 3
    private const val MAX_PER_MINUTE = 10

    private const val IDLE = 0
    private const val BUSY = 1
    private const val DONE = 2

    private class Progress(size: Int) {
        val states = IntArray(size)
        val generation = IntArray(size)
        val written = LongArray(size)
        val received = ArrayList<ReceivedFile>()
    }

    private val lock = Any()
    private val random = SecureRandom()
    private val progress = HashMap<String, Progress>()
    private val recentCreations = HashMap<String, ArrayDeque<Long>>()
    private val _requests = MutableStateFlow<List<UploadRequest>>(emptyList())
    private val _live = MutableStateFlow<Map<String, ReceiveProgress>>(emptyMap())
    @Volatile private var lastLive = 0L

    /** Requests the phone's owner can still act on or that are being received. */
    val requests: StateFlow<List<UploadRequest>> = _requests.asStateFlow()

    /** Progress of every incoming file, keyed "requestId:index". */
    val live: StateFlow<Map<String, ReceiveProgress>> = _live.asStateFlow()

    /** Called when a request needs an answer, so the service can raise a notification. */
    @Volatile var onRequest: ((UploadRequest) -> Unit)? = null

    /** Called when every file of a request has been saved. */
    @Volatile var onBatchDone: ((String, List<ReceivedFile>) -> Unit)? = null

    fun create(device: TrustedDevice, files: List<Triple<String, Long, String>>): UploadCreateResult {
        if (device.uploads == UploadPolicy.Blocked) return UploadCreateResult.Blocked
        if (files.isEmpty() || files.size > MAX_FILES) return UploadCreateResult.Invalid("bad_file_count")
        var total = 0L
        val specs = ArrayList<UploadFileSpec>()
        for ((rawName, size, fp) in files) {
            if (size < 0 || size > MAX_FILE_BYTES) return UploadCreateResult.Invalid("file_too_large")
            total += size
            specs += UploadFileSpec(ReceivedFiles.cleanName(rawName), size, fp.take(200))
        }
        if (total > MAX_TOTAL_BYTES) return UploadCreateResult.Invalid("too_large")

        val created: UploadRequest
        synchronized(lock) {
            val now = System.currentTimeMillis()
            purge(now)
            val times = recentCreations.getOrPut(device.id) { ArrayDeque() }
            while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
            val pending = _requests.value.count { it.deviceId == device.id && it.status == UploadStatus.Pending }
            if (times.size >= MAX_PER_MINUTE || pending >= MAX_PENDING_PER_DEVICE) return UploadCreateResult.TooMany
            times.addLast(now)
            val auto = device.uploads == UploadPolicy.Always
            created = UploadRequest(
                id = hex(16), deviceId = device.id, deviceName = device.label, files = specs, createdAt = now,
                status = if (auto) UploadStatus.Approved else UploadStatus.Pending, always = auto
            )
            val p = Progress(specs.size)
            // Show what an earlier, interrupted attempt already stored.
            specs.forEachIndexed { i, spec ->
                p.written[i] = Partials.length(Partials.file(device.id, spec.fingerprint, created.id, i), spec.size)
            }
            progress[created.id] = p
            _requests.value = _requests.value + created
            publishLive(force = true)
        }
        if (created.status == UploadStatus.Pending) {
            EventLog.add("${created.deviceName} asked to send ${created.files.size} file(s)")
            onRequest?.invoke(created)
        } else {
            EventLog.add("${created.deviceName} is sending ${created.files.size} file(s) (always allowed)")
        }
        return UploadCreateResult.Created(created)
    }

    /** The request, if it exists, still counts and belongs to this device. */
    fun find(id: String, deviceId: String): UploadRequest? = synchronized(lock) {
        purge(System.currentTimeMillis())
        _requests.value.firstOrNull { it.id == id && it.deviceId == deviceId }
    }

    /** Approves a waiting request. With [always], that device is also set to "always allow" from now on. */
    fun approve(id: String, always: Boolean) {
        var deviceId: String? = null
        synchronized(lock) {
            val now = System.currentTimeMillis()
            purge(now)
            val target = _requests.value.firstOrNull { it.id == id && it.status == UploadStatus.Pending } ?: return
            deviceId = target.deviceId
            // "Always" also settles anything else that device already has waiting.
            _requests.value = _requests.value.map {
                if (it.status == UploadStatus.Pending && (it.id == id || (always && it.deviceId == target.deviceId))) {
                    it.copy(status = UploadStatus.Approved, updatedAt = now, always = always)
                } else it
            }
        }
        val device = deviceId ?: return
        if (always) Pairing.setUploadPolicy(device, UploadPolicy.Always)
        EventLog.add(if (always) "You always allow uploads from a device" else "You approved an upload")
    }

    fun deny(id: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _requests.value = _requests.value.map {
                if (it.id == id && it.status == UploadStatus.Pending) it.copy(status = UploadStatus.Denied, updatedAt = now) else it
            }
        }
        EventLog.add("You denied an upload")
    }

    /** The sending device gave up: stop waiting and refuse any further bytes. */
    fun cancel(id: String, deviceId: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _requests.value = _requests.value.map {
                if (it.id == id && it.deviceId == deviceId && (it.status == UploadStatus.Pending || it.status == UploadStatus.Approved)) {
                    it.copy(status = UploadStatus.Expired, updatedAt = now)
                } else it
            }
        }
    }

    /**
     * Changes what a device may do. Allowing it approves what it already has waiting; blocking denies it.
     * This is the one entry point the settings screen uses.
     */
    fun setPolicy(deviceId: String, policy: UploadPolicy) {
        Pairing.setUploadPolicy(deviceId, policy)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _requests.value = _requests.value.map {
                if (it.deviceId == deviceId && it.status == UploadStatus.Pending) {
                    when (policy) {
                        UploadPolicy.Always -> it.copy(status = UploadStatus.Approved, updatedAt = now, always = true)
                        UploadPolicy.Blocked -> it.copy(status = UploadStatus.Denied, updatedAt = now)
                        UploadPolicy.Ask -> it
                    }
                } else if (policy == UploadPolicy.Blocked && it.deviceId == deviceId && it.status == UploadStatus.Approved) {
                    it.copy(status = UploadStatus.Expired, updatedAt = now)
                } else it
            }
        }
    }

    /** The spec of one file of this device's request, and whether it has already been received. Null if unknown. */
    fun specOf(id: String, deviceId: String, index: Int): Pair<UploadFileSpec, Boolean>? = synchronized(lock) {
        purge(System.currentTimeMillis())
        val request = _requests.value.firstOrNull { it.id == id && it.deviceId == deviceId } ?: return@synchronized null
        if (request.status != UploadStatus.Approved && request.status != UploadStatus.Done) return@synchronized null
        val p = progress[id] ?: return@synchronized null
        if (index < 0 || index >= request.files.size) return@synchronized null
        request.files[index] to (p.states[index] == DONE)
    }

    /**
     * Reserves one file of an approved request. Returns null if it is not approved or already received. If an older
     * connection is still marked as writing (its device vanished without a goodbye), the new one takes over.
     */
    fun beginFile(id: String, deviceId: String, index: Int): UploadSlot? = synchronized(lock) {
        val now = System.currentTimeMillis()
        purge(now)
        val request = _requests.value.firstOrNull { it.id == id && it.deviceId == deviceId && it.status == UploadStatus.Approved }
            ?: return@synchronized null
        val p = progress[id] ?: return@synchronized null
        if (index < 0 || index >= request.files.size || p.states[index] == DONE) return@synchronized null
        p.states[index] = BUSY
        p.generation[index]++
        // Receiving counts as activity, so the approval does not run out in the middle of a big file.
        _requests.value = _requests.value.map { if (it.id == id) it.copy(updatedAt = now) else it }
        publishLive(force = true)
        UploadSlot(request, index, request.files[index], p.generation[index])
    }

    /** False once a newer connection has taken this file over: the older one must stop writing. */
    fun isCurrent(slot: UploadSlot): Boolean = synchronized(lock) {
        progress[slot.request.id]?.generation?.getOrNull(slot.index) == slot.generation
    }

    /** [bytes] of the file are now stored. Called often; the screen is refreshed a few times a second. */
    fun report(slot: UploadSlot, bytes: Long) {
        synchronized(lock) {
            val p = progress[slot.request.id] ?: return
            if (p.generation[slot.index] != slot.generation) return
            p.written[slot.index] = bytes
            val now = System.currentTimeMillis()
            val r = _requests.value.firstOrNull { it.id == slot.request.id }
            if (r != null && now - r.updatedAt > 60_000) {
                _requests.value = _requests.value.map { if (it.id == r.id) it.copy(updatedAt = now) else it }
            }
            publishLive(force = false)
        }
    }

    /** The file did not fully arrive (dropped connection, full storage). What arrived is kept; the device can continue. */
    fun releaseFile(slot: UploadSlot, bytes: Long) {
        synchronized(lock) {
            val p = progress[slot.request.id] ?: return
            if (p.generation[slot.index] != slot.generation) return
            if (p.states[slot.index] == BUSY) p.states[slot.index] = IDLE
            p.written[slot.index] = bytes
            publishLive(force = true)
        }
    }

    /** The file was saved. When it was the last one, the request is finished. */
    fun completeFile(slot: UploadSlot, saved: ReceivedFile) {
        var finished: List<ReceivedFile>? = null
        synchronized(lock) {
            val p = progress[slot.request.id] ?: return
            p.states[slot.index] = DONE
            p.written[slot.index] = slot.spec.size
            p.received += saved
            if (p.states.all { it == DONE }) {
                val now = System.currentTimeMillis()
                _requests.value = _requests.value.map {
                    if (it.id == slot.request.id) it.copy(status = UploadStatus.Done, updatedAt = now) else it
                }
                finished = p.received.toList()
            }
            publishLive(force = true)
        }
        ReceivedFiles.record(saved)
        EventLog.add("Received \"${saved.name}\" from ${saved.from}")
        finished?.let { onBatchDone?.invoke(saved.from, it) }
    }

    /** Call with [lock] held. */
    private fun publishLive(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLive < 250) return
        lastLive = now
        val out = HashMap<String, ReceiveProgress>()
        for (request in _requests.value) {
            val p = progress[request.id] ?: continue
            for (i in request.files.indices) {
                val state = when {
                    p.states[i] == DONE -> ReceiveState.Done
                    p.states[i] == BUSY -> ReceiveState.Receiving
                    p.written[i] > 0 -> ReceiveState.Paused
                    else -> ReceiveState.Waiting
                }
                out["${request.id}:$i"] = ReceiveProgress(p.written[i], state)
            }
        }
        _live.value = out
    }

    /** Called when hosting stops: nothing can be received any more. */
    fun clearAll() {
        synchronized(lock) {
            _requests.value = emptyList()
            progress.clear()
            recentCreations.clear()
            _live.value = emptyMap()
        }
    }

    private fun purge(now: Long) {
        val keep = _requests.value.filter {
            when (it.status) {
                UploadStatus.Pending -> now - it.createdAt < PENDING_TTL_MS
                UploadStatus.Approved -> now - it.updatedAt < APPROVED_TTL_MS
                else -> now - it.updatedAt < FINISHED_TTL_MS
            }
        }
        if (keep.size != _requests.value.size) {
            _requests.value = keep
            progress.keys.retainAll(keep.map { it.id }.toSet())
            publishLive(force = true)
        }
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
