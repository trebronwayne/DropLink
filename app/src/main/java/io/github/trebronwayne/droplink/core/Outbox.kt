package io.github.trebronwayne.droplink.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** Where one file stands for one device. */
enum class DeliveryState {
    /** The device has not started downloading it. */
    Waiting,
    /** Bytes are flowing. */
    Downloading,
    /** The device has every byte. */
    Done,
    /** The download stopped part-way. It continues from the same place when the device asks again. */
    Interrupted
}

/** A file the phone's owner chose to send. [uri] is the Android document the bytes are read from. */
data class OutFile(val index: Int, val name: String, val size: Long, val mime: String, val uri: String)

data class OutTarget(val deviceId: String, val name: String)

/** [bytes] is the furthest point of the file the device has received. */
data class OutDelivery(val deviceId: String, val index: Int, val bytes: Long, val state: DeliveryState)

/** One "send these files to these devices" action. */
data class OutBatch(
    val id: String,
    val createdAt: Long,
    val files: List<OutFile>,
    val targets: List<OutTarget>,
    /** True when it was addressed to everyone who was connected at that moment. */
    val everyone: Boolean,
    val cancelled: Boolean,
    val deliveries: List<OutDelivery>
) {
    fun delivery(deviceId: String, index: Int): OutDelivery =
        deliveries.firstOrNull { it.deviceId == deviceId && it.index == index } ?: OutDelivery(deviceId, index, 0L, DeliveryState.Waiting)

    val finished: Boolean get() = deliveries.isNotEmpty() && deliveries.all { it.state == DeliveryState.Done }
}

/**
 * Sending files from this phone to paired devices. The owner picks files and devices; each chosen device sees the
 * files in its web page and downloads them. Because devices download over normal HTTP with byte ranges, a stopped
 * download continues where it left off, and the progress here follows the furthest byte each device has received.
 */
object Outbox {
    const val TTL_MS = 24L * 3600 * 1000
    const val MAX_FILES = 100
    private const val MAX_BATCHES = 20

    private class Mut(
        val id: String, val createdAt: Long, val files: List<OutFile>, val targets: List<OutTarget>, val everyone: Boolean
    ) {
        var cancelled = false
        val bytes = HashMap<String, Long>()
        val state = HashMap<String, DeliveryState>()
    }

    private val lock = Any()
    private val random = SecureRandom()
    private val items = LinkedHashMap<String, Mut>()
    private val _batches = MutableStateFlow<List<OutBatch>>(emptyList())
    @Volatile private var lastPublish = 0L

    /** Newest first. */
    val batches: StateFlow<List<OutBatch>> = _batches.asStateFlow()

    /** Hands out the right to report progress for one device downloading one file. */
    class Serve internal constructor(
        private val batchId: String, private val deviceId: String, private val index: Int, private val size: Long
    ) {
        /** [position] is the offset in the file the device has now reached. */
        fun advance(position: Long) = progress(batchId, deviceId, index, position, size, finished = false)

        /** The connection ended with the device at [position]. Complete if that is the end of the file. */
        fun finish(position: Long) = progress(batchId, deviceId, index, position, size, finished = true)
    }

    fun create(files: List<OutFile>, targets: List<TrustedDevice>, everyone: Boolean): OutBatch? {
        if (files.isEmpty() || targets.isEmpty()) return null
        val batch: OutBatch
        synchronized(lock) {
            purge(System.currentTimeMillis())
            val m = Mut(
                hex(12), System.currentTimeMillis(), files.take(MAX_FILES).mapIndexed { i, f -> f.copy(index = i) },
                targets.map { OutTarget(it.id, it.label) }, everyone
            )
            items[m.id] = m
            while (items.size > MAX_BATCHES) items.remove(items.keys.first())
            publish(force = true)
            batch = view(m)
        }
        EventLog.add("Sending ${batch.files.size} file(s) to ${batch.targets.size} device(s)")
        return batch
    }

    /** Stops offering the files. Devices that are mid-download keep what they have but cannot continue. */
    fun cancel(id: String) {
        synchronized(lock) {
            items[id]?.cancelled = true
            publish(force = true)
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            items.remove(id)
            publish(force = true)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            items.clear()
            publish(force = true)
        }
    }

    /** What this device may fetch: batches addressed to it that are still on offer. Newest first. */
    fun batchesFor(deviceId: String): List<OutBatch> = synchronized(lock) {
        purge(System.currentTimeMillis())
        items.values.filter { !it.cancelled && it.targets.any { t -> t.deviceId == deviceId } }.map { view(it) }.reversed()
    }

    /** The file if this device is a recipient and the batch is still on offer, otherwise null. */
    fun file(batchId: String, deviceId: String, index: Int): OutFile? = synchronized(lock) {
        purge(System.currentTimeMillis())
        val m = items[batchId] ?: return@synchronized null
        if (m.cancelled || m.targets.none { it.deviceId == deviceId }) return@synchronized null
        m.files.getOrNull(index)
    }

    fun serve(batchId: String, deviceId: String, file: OutFile): Serve = Serve(batchId, deviceId, file.index, file.size)

    private fun progress(batchId: String, deviceId: String, index: Int, position: Long, size: Long, finished: Boolean) {
        synchronized(lock) {
            val m = items[batchId] ?: return
            val key = "$deviceId:$index"
            val furthest = maxOf(m.bytes[key] ?: 0L, position.coerceAtMost(size))
            m.bytes[key] = furthest
            val before = m.state[key]
            val after = when {
                finished && furthest >= size -> DeliveryState.Done
                finished -> DeliveryState.Interrupted
                before == DeliveryState.Done -> DeliveryState.Done
                else -> DeliveryState.Downloading
            }
            m.state[key] = after
            publish(force = finished || before != after)
        }
    }

    private fun view(m: Mut): OutBatch {
        val deliveries = ArrayList<OutDelivery>(m.targets.size * m.files.size)
        for (t in m.targets) for (f in m.files) {
            val key = "${t.deviceId}:${f.index}"
            deliveries += OutDelivery(t.deviceId, f.index, m.bytes[key] ?: 0L, m.state[key] ?: DeliveryState.Waiting)
        }
        return OutBatch(m.id, m.createdAt, m.files, m.targets, m.everyone, m.cancelled, deliveries)
    }

    /** Call with [lock] held. Progress updates are spread out; state changes always go through. */
    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 250) return
        lastPublish = now
        _batches.value = items.values.map { view(it) }.reversed()
    }

    private fun purge(now: Long) {
        val before = items.size
        items.values.removeAll { now - it.createdAt > TTL_MS }
        if (items.size != before) publish(force = true)
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
