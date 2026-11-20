package io.github.trebronwayne.droplink.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Looks for the lyrics of a song and the subtitles of a video, in this order for songs:
 * a synced file next to the song (.lrc, .lyr, .srt ...), synced lyrics inside the file, a plain text file next to it,
 * and plain lyrics inside the file. For videos every subtitle file found becomes a track to choose from.
 */
object MediaTextRepo {
    private val cache = object : LinkedHashMap<String, MediaText>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaText>?) = size > 40
    }

    @Synchronized private fun cached(id: String): MediaText? = cache[id]
    @Synchronized private fun remember(id: String, text: MediaText) { cache[id] = text }
    @Synchronized fun forget(id: String) { cache.remove(id) }

    suspend fun load(context: Context, entry: MediaEntry): MediaText {
        cached(entry.id)?.let { return it }
        val app = context.applicationContext
        val result = withContext(Dispatchers.IO) { if (entry.isVideo) forVideo(app, entry) else forSong(app, entry) }
        remember(entry.id, result)
        return result
    }

    private val ORDER = listOf("lrc", "lyr", "srt", "vtt", "ass", "ssa", "txt")

    private suspend fun forSong(context: Context, entry: MediaEntry): MediaText {
        val files = Sidecars.find(context, entry)
            .sortedBy { val i = ORDER.indexOf(it.ext); if (i < 0) 99 else i }
        val parsed = files.mapNotNull { f ->
            val bytes = Sidecars.readBytes(context, f) ?: return@mapNotNull null
            TextParsers.lyrics(TextParsers.decode(bytes), f.ext, f.name).takeIf { !it.isEmpty }
        }
        parsed.firstOrNull { it.isSynced }?.let { return MediaText(it, emptyList()) }
        val embedded = try {
            context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use { EmbeddedLyrics.read(it, entry.displayName) }
        } catch (_: Exception) { null }
        if (embedded != null && embedded.isSynced) return MediaText(embedded, emptyList())
        val plain = parsed.firstOrNull() ?: embedded
        return MediaText(plain, emptyList())
    }

    private suspend fun forVideo(context: Context, entry: MediaEntry): MediaText {
        val files = Sidecars.find(context, entry)
            .filter { TextParsers.isSubtitleExt(it.ext) }
            .sortedBy { val i = ORDER.indexOf(it.ext); if (i < 0) 99 else i }
        val tracks = files.mapNotNull { f ->
            val bytes = Sidecars.readBytes(context, f) ?: return@mapNotNull null
            val cues = TextParsers.subtitles(TextParsers.decode(bytes), f.ext)
            if (cues.isEmpty()) null else SubtitleTrack(Sidecars.trackName(entry, f), f.name, cues)
        }
        return MediaText(null, tracks)
    }
}
