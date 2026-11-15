package io.github.trebronwayne.droplink.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The phone's music and videos, newest first.
 *
 * Only common formats are listed, so the list is music and video, not ringtones, notification sounds or voice notes:
 * music in mp3, m4a, aac, wav, flac or ogg, and video in mp4, mkv, webm, 3gp, m4v or mov. "Newest" means when the file
 * appeared on the phone (a download, a received file, a copy), so something that was just received is at the top,
 * whatever the date inside the file says. Music and video are sorted together.
 */
object MediaLibrary {
    val MUSIC_EXT = listOf("mp3", "m4a", "aac", "wav", "flac", "ogg")
    val VIDEO_EXT = listOf("mp4", "mkv", "webm", "3gp", "m4v", "mov")

    private val _items = MutableStateFlow<List<MediaEntry>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    @Volatile private var byId: Map<String, MediaEntry> = emptyMap()

    /** Newest first, music and video together. */
    val items: StateFlow<List<MediaEntry>> = _items.asStateFlow()
    /** True once a scan has finished, so the screen can tell "still looking" from "nothing found". */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun find(id: String): MediaEntry? = byId[id]

    /** Scans again. Needs permission to read audio and video; without it the list is empty. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val list = withContext(Dispatchers.IO) {
            try { scan(app) } catch (_: SecurityException) { emptyList() } catch (_: Exception) { emptyList() }
        }
        byId = list.associateBy { it.id }
        _items.value = list
        _loaded.value = true
    }

    private fun extClause(column: String, exts: List<String>) = exts.joinToString(" OR ", "(", ")") { "$column LIKE '%.$it'" }

    private fun scan(context: Context): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        val resolver = context.contentResolver

        val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val audioCols = ArrayList(listOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA
        ))
        if (Build.VERSION.SDK_INT >= 29) audioCols += MediaStore.Audio.Media.RELATIVE_PATH
        val audioWhere = extClause(MediaStore.Audio.Media.DISPLAY_NAME, MUSIC_EXT) +
            " AND ${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND ${MediaStore.Audio.Media.IS_ALARM}=0" +
            " AND ${MediaStore.Audio.Media.DURATION}>=${PlayerPrefs.minMusicSeconds.value * 1000L}"
        resolver.query(audioUri, audioCols.toTypedArray(), audioWhere, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: continue
                out += MediaEntry(
                    id = "a:$id", uri = ContentUris.withAppendedId(audioUri, id).toString(), displayName = name,
                    title = known(c.getString(iTitle)).ifBlank { name.substringBeforeLast('.') },
                    artist = known(c.getString(iArtist)), album = known(c.getString(iAlbum)),
                    durationMs = c.getLong(iDur), size = c.getLong(iSize), mime = c.getString(iMime) ?: "audio/*", isVideo = false,
                    dateAdded = c.getLong(iAdded), dateModified = c.getLong(iMod), albumId = c.getLong(iAlbumId),
                    folder = if (iRel >= 0) c.getString(iRel).orEmpty() else "", path = c.getString(iData).orEmpty()
                )
            }
        }

        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videoCols = ArrayList(listOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DATA
        ))
        if (Build.VERSION.SDK_INT >= 29) videoCols += MediaStore.Video.Media.RELATIVE_PATH
        resolver.query(videoUri, videoCols.toTypedArray(), extClause(MediaStore.Video.Media.DISPLAY_NAME, VIDEO_EXT), null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: continue
                out += MediaEntry(
                    id = "v:$id", uri = ContentUris.withAppendedId(videoUri, id).toString(), displayName = name,
                    title = known(c.getString(iTitle)).ifBlank { name.substringBeforeLast('.') }, artist = "", album = "",
                    durationMs = c.getLong(iDur), size = c.getLong(iSize), mime = c.getString(iMime) ?: "video/*", isVideo = true,
                    dateAdded = c.getLong(iAdded), dateModified = c.getLong(iMod),
                    folder = if (iRel >= 0) c.getString(iRel).orEmpty() else "", path = c.getString(iData).orEmpty()
                )
            }
        }
        // Newest first. Added date decides; the modified date and the id only break ties, so the order never jumps around.
        return out.sortedWith(
            compareByDescending<MediaEntry> { it.dateAdded }.thenByDescending { it.dateModified }.thenByDescending { it.id }
        )
    }

    private fun known(value: String?): String = if (value == null || value == MediaStore.UNKNOWN_STRING) "" else value.trim()

    /** Describes a file opened from elsewhere (a received file, a link from a file manager), so it can be played too. */
    fun fromUri(context: Context, uri: Uri): MediaEntry? {
        // The scanned list knows more (title, artist), so prefer it when the same file is in there.
        _items.value.firstOrNull { it.uri == uri.toString() }?.let { return it }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "media"
        var size = 0L
        var mime = ""
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(0)?.let { n -> name = n }
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
        } catch (_: Exception) { }
        mime = context.contentResolver.getType(uri).orEmpty()
        val video = mime.startsWith("video/") || (mime.isBlank() && VIDEO_EXT.any { name.lowercase().endsWith(".$it") })
        val audio = mime.startsWith("audio/") || (mime.isBlank() && MUSIC_EXT.any { name.lowercase().endsWith(".$it") })
        if (!video && !audio) return null
        var duration = 0L
        try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(context, uri)
            duration = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
        } catch (_: Exception) { }
        val now = System.currentTimeMillis() / 1000
        return MediaEntry(
            id = "u:" + Integer.toHexString(uri.toString().hashCode()), uri = uri.toString(), displayName = name,
            title = name.substringBeforeLast('.'), artist = "", album = "", durationMs = duration, size = size,
            mime = mime.ifBlank { if (video) "video/*" else "audio/*" }, isVideo = video, dateAdded = now, dateModified = now
        )
    }
}
