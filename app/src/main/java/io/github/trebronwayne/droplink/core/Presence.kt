package io.github.trebronwayne.droplink.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** How reachable a paired device is right now. */
enum class PresenceStatus { Online, Recent, Offline }

/**
 * Which paired devices are around. Every request a device makes counts as a sign of life, and the web client also
 * checks in every few seconds while its page is open. "Online" means seen a moment ago; "Recent" means seen in the
 * last few minutes (a phone that locked its screen, a tab in the background); anything older is "Offline".
 * Files can be sent to devices that are online or recent.
 */
object Presence {
    const val ONLINE_MS = 25_000L
    const val RECENT_MS = 10 * 60_000L

    private const val PREFS = "droplink_presence"
    private const val KEY = "seen"

    private val seen = ConcurrentHashMap<String, Long>()
    private val _state = MutableStateFlow<Map<String, Long>>(emptyMap())
    @Volatile private var prefs: android.content.SharedPreferences? = null
    @Volatile private var lastPublish = 0L
    @Volatile private var lastSave = 0L

    /** Device id to the time it was last seen. */
    val state: StateFlow<Map<String, Long>> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        try {
            val o = JSONObject(p.getString(KEY, "{}") ?: "{}")
            o.keys().forEach { seen.putIfAbsent(it, o.optLong(it)) }
        } catch (_: Exception) { }
        _state.value = HashMap(seen)
    }

    /** This device just made contact. */
    fun touch(deviceId: String) {
        val now = System.currentTimeMillis()
        val before = seen.put(deviceId, now)
        val wasQuiet = before == null || now - before > ONLINE_MS
        if (wasQuiet || now - lastPublish > 1_500) {
            lastPublish = now
            _state.value = HashMap(seen)
        }
        if (now - lastSave > 30_000) {
            lastSave = now
            save()
        }
    }

    fun forget(deviceId: String) {
        seen.remove(deviceId)
        _state.value = HashMap(seen)
        save()
    }

    fun lastSeen(deviceId: String): Long? = seen[deviceId]

    fun status(lastSeen: Long?, now: Long = System.currentTimeMillis()): PresenceStatus = when {
        lastSeen == null -> PresenceStatus.Offline
        now - lastSeen <= ONLINE_MS -> PresenceStatus.Online
        now - lastSeen <= RECENT_MS -> PresenceStatus.Recent
        else -> PresenceStatus.Offline
    }

    fun statusOf(deviceId: String): PresenceStatus = status(seen[deviceId])

    private fun save() {
        try {
            val o = JSONObject()
            seen.forEach { (k, v) -> o.put(k, v) }
            prefs?.edit()?.putString(KEY, o.toString())?.apply()
        } catch (_: Exception) { }
    }
}
