package io.github.trebronwayne.droplink.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Cover art for songs and a still frame for videos, cached in memory. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())

    suspend fun load(context: Context, entry: MediaEntry, width: Int, height: Int): Bitmap? {
        val key = "${entry.id}:$width"
        cache.get(key)?.let { return it }
        if (key in missing) return null
        val bmp = withContext(Dispatchers.IO) { fetch(context.applicationContext, entry, width, height) }
        if (bmp != null) cache.put(key, bmp) else missing.add(key)
        return bmp
    }

    private fun fetch(context: Context, entry: MediaEntry, width: Int, height: Int): Bitmap? {
        val uri = Uri.parse(entry.uri)
        if (Build.VERSION.SDK_INT >= 29) {
            try { return context.contentResolver.loadThumbnail(uri, Size(width, height), null) } catch (_: Exception) { }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            if (entry.isVideo) r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            else r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) { } }
    }

    /** A JPEG of the cover or frame for the web page, or null if there is none. */
    suspend fun jpeg(context: Context, entry: MediaEntry): ByteArray? {
        val bmp = load(context, entry, 512, 512) ?: return null
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }
}
