package io.github.trebronwayne.droplink.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live media streaming. While it is on, paired devices can open the player in their browser: they see what is
 * playing on this phone and watch or listen in sync with it. Only the items in the phone's current queue can be
 * fetched, never the rest of the phone's library. It is off every time the app starts.
 */
object LiveStream {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) { _enabled.value = on }

    /** The item with this id, only if streaming is on and it is part of the queue. */
    fun entry(id: String): MediaEntry? = if (!_enabled.value) null else PlayerHub.queueSnapshot.firstOrNull { it.id == id }

    private fun json(e: MediaEntry) = JSONObject()
        .put("id", e.id).put("title", e.title).put("artist", e.artist).put("album", e.album)
        .put("kind", if (e.isVideo) "video" else "audio").put("durationMs", e.durationMs).put("size", e.size).put("mime", e.mime)

    /** What the web page polls. The position is already moved forward to now when the item is playing. */
    fun stateJson(): String {
        if (!_enabled.value) return JSONObject().put("on", false).toString()
        val queue = PlayerHub.queueSnapshot
        val snap = PlayerHub.snapshot
        val now = queue.firstOrNull { it.id == snap.entryId }
        val position = snap.positionMs + if (snap.playing && snap.at > 0) ((System.currentTimeMillis() - snap.at).coerceAtLeast(0) * snap.speed).toLong() else 0L
        return JSONObject()
            .put("on", true)
            .put("playing", snap.playing)
            .put("speed", snap.speed.toDouble())
            .put("index", snap.index)
            .put("positionMs", position)
            .put("durationMs", snap.durationMs)
            .put("now", if (now != null) json(now) else JSONObject.NULL)
            .put("queue", JSONArray().also { a -> queue.take(300).forEach { a.put(json(it)) } })
            .toString()
    }

    private fun cues(list: List<Cue>) = JSONArray().also { a -> list.forEach { a.put(JSONArray().put(it.startMs).put(it.endMs).put(it.text)) } }

    /** Lyrics and subtitles of one item, as the web page wants them. */
    fun textJson(t: MediaText): String {
        val lyrics = t.lyrics
        return JSONObject()
            .put("lyrics", if (lyrics == null || lyrics.isEmpty) JSONObject.NULL else JSONObject()
                .put("synced", lyrics.isSynced).put("cues", cues(lyrics.synced)).put("plain", lyrics.plain).put("source", lyrics.source))
            .put("subtitles", JSONArray().also { a -> t.subtitles.forEach { a.put(JSONObject().put("name", it.name).put("cues", cues(it.cues))) } })
            .toString()
    }
}
