package io.github.trebronwayne.droplink.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the player remembers: favorites, what was played recently, and where each video was left off.
 *
 * A file is remembered by its id and also by its name and size, so a favorite or a saved position still finds the
 * file if Android renumbers its media database.
 */
object PlayerStore {
    private const val PREFS = "droplink_player_store"
    private const val MAX_RECENT = 60
    private const val MAX_RESUME = 150
    /** A video within this much of its end counts as finished. */
    private const val FINISHED_TAIL_MS = 15_000L
    private const val MIN_RESUME_MS = 10_000L

    /** Identifies a file even if its id changes. */
    data class Ref(val id: String, val name: String, val size: Long) {
        fun matches(e: MediaEntry) = id == e.id || (name == e.displayName && size == e.size)
    }
    data class Favorite(val ref: Ref, val at: Long)
    data class Played(val ref: Ref, val at: Long)
    data class Resume(val ref: Ref, val positionMs: Long, val durationMs: Long, val at: Long) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    @Volatile private var prefs: android.content.SharedPreferences? = null
    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    private val _recent = MutableStateFlow<List<Played>>(emptyList())
    private val _resume = MutableStateFlow<List<Resume>>(emptyList())

    /** Newest favorite first. */
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()
    /** Most recently played first. */
    val recent: StateFlow<List<Played>> = _recent.asStateFlow()
    /** Unfinished videos, most recently watched first. */
    val resume: StateFlow<List<Resume>> = _resume.asStateFlow()

    private fun ref(e: MediaEntry) = Ref(e.id, e.displayName, e.size)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _favorites.value = readList(p.getString("favorites", null)) { o, r -> Favorite(r, o.optLong("at")) }
        _recent.value = readList(p.getString("recent", null)) { o, r -> Played(r, o.optLong("at")) }
        _resume.value = readList(p.getString("resume", null)) { o, r -> Resume(r, o.optLong("pos"), o.optLong("dur"), o.optLong("at")) }
    }

    private fun <T> readList(json: String?, build: (JSONObject, Ref) -> T): List<T> = try {
        val a = JSONArray(json ?: "[]")
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            build(o, Ref(o.getString("id"), o.optString("name"), o.optLong("size")))
        }
    } catch (_: Exception) { emptyList() }

    private fun JSONObject.ref(r: Ref): JSONObject = put("id", r.id).put("name", r.name).put("size", r.size)

    private fun save() {
        val p = prefs ?: return
        p.edit()
            .putString("favorites", JSONArray().also { a -> _favorites.value.forEach { a.put(JSONObject().ref(it.ref).put("at", it.at)) } }.toString())
            .putString("recent", JSONArray().also { a -> _recent.value.forEach { a.put(JSONObject().ref(it.ref).put("at", it.at)) } }.toString())
            .putString("resume", JSONArray().also { a -> _resume.value.forEach { a.put(JSONObject().ref(it.ref).put("pos", it.positionMs).put("dur", it.durationMs).put("at", it.at)) } }.toString())
            .apply()
    }

    /* ---- favorites ---- */
    fun isFavorite(e: MediaEntry) = _favorites.value.any { it.ref.matches(e) }

    fun toggleFavorite(e: MediaEntry) {
        _favorites.value = if (isFavorite(e)) _favorites.value.filterNot { it.ref.matches(e) }
        else listOf(Favorite(ref(e), System.currentTimeMillis())) + _favorites.value
        save()
    }

    /* ---- recent ---- */
    fun recordPlayed(e: MediaEntry) {
        _recent.value = (listOf(Played(ref(e), System.currentTimeMillis())) + _recent.value.filterNot { it.ref.matches(e) }).take(MAX_RECENT)
        save()
    }

    fun clearRecent() { _recent.value = emptyList(); save() }

    fun removeRecent(e: MediaEntry) { _recent.value = _recent.value.filterNot { it.ref.matches(e) }; save() }

    /* ---- where a video was left off ---- */

    /** Remembers [positionMs]. A video that is nearly over is marked finished and forgotten. Only videos are remembered. */
    fun savePosition(e: MediaEntry, positionMs: Long, durationMs: Long) {
        if (!e.isVideo) return
        val dur = if (durationMs > 0) durationMs else e.durationMs
        val without = _resume.value.filterNot { it.ref.matches(e) }
        _resume.value = if (positionMs < MIN_RESUME_MS || (dur > 0 && positionMs >= dur - FINISHED_TAIL_MS)) without
        else (listOf(Resume(ref(e), positionMs, dur, System.currentTimeMillis())) + without).take(MAX_RESUME)
        save()
    }

    fun resumeOf(e: MediaEntry): Resume? = if (!e.isVideo) null else _resume.value.firstOrNull { it.ref.matches(e) }

    fun forgetPosition(e: MediaEntry) { _resume.value = _resume.value.filterNot { it.ref.matches(e) }; save() }

    fun clearPositions() { _resume.value = emptyList(); save() }

    /** The files behind a list of references, in the same order; files that no longer exist are skipped. */
    fun <T> resolve(items: List<T>, ref: (T) -> Ref, library: List<MediaEntry>): List<MediaEntry> {
        val byId = library.associateBy { it.id }
        val byName = library.groupBy { it.displayName to it.size }
        return items.mapNotNull { byId[ref(it).id] ?: byName[ref(it).name to ref(it).size]?.firstOrNull() }.distinctBy { it.id }
    }
}
