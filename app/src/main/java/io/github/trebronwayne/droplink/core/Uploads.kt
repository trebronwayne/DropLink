package io.github.trebronwayne.droplink.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

enum class UploadStatus { Pending, Approved, Denied, Expired, Done }

/** One file a device wants to send. The name is already cleaned; the size is what the device announced. */
data class UploadFileSpec(val name: String, val size: Long)

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

/** One file of an approved request that a device may now send. */
class UploadSlot(val request: UploadRequest, val index: Int, val spec: UploadFileSpec)

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
        val received = ArrayList<ReceivedFile>()
    }

    private val lock = Any()
    private val random = SecureRandom()
    private val progress = HashMap<String, Progress>()
    private val recentCreations = HashMap<String, ArrayDeque<Long>>()
    private val _requests = MutableStateFlow<List<UploadRequest>>(emptyList())

    /** Requests the phone's owner can still act on or that are being received. */
    val requests: StateFlow<List<UploadRequest>> = _requests.asStateFlow()

    /** Called when a request needs an answer, so the service can raise a notification. */
    @Volatile var onRequest: ((UploadRequest) -> Unit)? = null

    /** Called when every file of a request has been saved. */
    @Volatile var onBatchDone: ((String, List<ReceivedFile>) -> Unit)? = null

    fun create(device: TrustedDevice, files: List<Pair<String, Long>>): UploadCreateResult {
        if (device.uploads == UploadPolicy.Blocked) return UploadCreateResult.Blocked
        if (files.isEmpty() || files.size > MAX_FILES) return UploadCreateResult.Invalid("bad_file_count")
        var total = 0L
        val specs = ArrayList<UploadFileSpec>()
        for ((rawName, size) in files) {
            if (size < 0 || size > MAX_FILE_BYTES) return UploadCreateResult.Invalid("file_too_large")
            total += size
            specs += UploadFileSpec(ReceivedFiles.cleanName(rawName), size)
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
                id = hex(16), deviceId = device.id, deviceName = device.name, files = specs, createdAt = now,
                status = if (auto) UploadStatus.Approved else UploadStatus.Pending, always = auto
            )
            progress[created.id] = Progress(specs.size)
            _requests.value = _requests.value + created
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

    /** Reserves one file of an approved request. Returns null if it is not approved, already received, or being received. */
    fun beginFile(id: String, deviceId: String, index: Int): UploadSlot? = synchronized(lock) {
        purge(System.currentTimeMillis())
        val request = _requests.value.firstOrNull { it.id == id && it.deviceId == deviceId && it.status == UploadStatus.Approved }
            ?: return@synchronized null
        val p = progress[id] ?: return@synchronized null
        if (index < 0 || index >= request.files.size || p.states[index] != IDLE) return@synchronized null
        p.states[index] = BUSY
        UploadSlot(request, index, request.files[index])
    }

    /** The file did not arrive (dropped connection, full storage): let the device try that file again. */
    fun releaseFile(slot: UploadSlot) {
        synchronized(lock) {
            val p = progress[slot.request.id] ?: return
            if (p.states[slot.index] == BUSY) p.states[slot.index] = IDLE
        }
    }

    /** The file was saved. When it was the last one, the request is finished. */
    fun completeFile(slot: UploadSlot, saved: ReceivedFile) {
        var finished: List<ReceivedFile>? = null
        synchronized(lock) {
            val p = progress[slot.request.id] ?: return
            p.states[slot.index] = DONE
            p.received += saved
            if (p.states.all { it == DONE }) {
                val now = System.currentTimeMillis()
                _requests.value = _requests.value.map {
                    if (it.id == slot.request.id) it.copy(status = UploadStatus.Done, updatedAt = now) else it
                }
                finished = p.received.toList()
            }
        }
        ReceivedFiles.record(saved)
        EventLog.add("Received \"${saved.name}\" from ${saved.from}")
        finished?.let { onBatchDone?.invoke(saved.from, it) }
    }

    /** Called when hosting stops: nothing can be received any more. */
    fun clearAll() {
        synchronized(lock) {
            _requests.value = emptyList()
            progress.clear()
            recentCreations.clear()
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
        }
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
