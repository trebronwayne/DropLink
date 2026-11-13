package io.github.trebronwayne.droplink.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class StatsSnapshot(val activeTransfers: Int, val totalBytes: Long, val bytesPerSecond: Long)

/** Live server throughput: how many transfers are running, how much was served, and the current speed. */
object ServerStats {
    private val active = AtomicInteger(0)
    private val total = AtomicLong(0)
    private val _state = MutableStateFlow(StatsSnapshot(0, 0L, 0L))
    val state: StateFlow<StatsSnapshot> = _state.asStateFlow()
    @Volatile private var sampler: Thread? = null

    fun begin() {
        active.incrementAndGet()
    }

    fun end() {
        active.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun add(bytes: Long) {
        total.addAndGet(bytes)
    }

    @Synchronized
    fun start() {
        if (sampler != null) return
        active.set(0)
        total.set(0)
        val thread = Thread {
            var last = 0L
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    val now = total.get()
                    _state.value = StatsSnapshot(active.get(), now, now - last)
                    last = now
                }
            } catch (_: InterruptedException) {
                // Stopped.
            }
        }
        thread.name = "DropLink-Stats"
        thread.isDaemon = true
        sampler = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        sampler?.interrupt()
        sampler = null
        active.set(0)
        _state.value = StatsSnapshot(0, 0L, 0L)
    }
}
