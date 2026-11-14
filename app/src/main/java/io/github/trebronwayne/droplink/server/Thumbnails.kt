package io.github.trebronwayne.droplink.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.DocumentsContract
import android.util.LruCache
import android.util.Size
import io.github.trebronwayne.droplink.core.SharedFolders
import java.io.ByteArrayOutputStream

/** Small JPEG previews so the grid never downloads full-size photos. Kept in a memory cache. */
object Thumbnails {
    private const val EDGE = 320
    private val cache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    fun get(context: Context, share: String, path: String): ByteArray? {
        val entry = SharedFolders.entry(context, share, path) ?: return null
        if (entry.isDir) return null
        val key = "$share|$path|${entry.size}|${entry.modified}"
        cache.get(key)?.let { return it }
        val bitmap = decode(context, entry.uri, entry.mime) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
        bitmap.recycle()
        return out.toByteArray().also { cache.put(key, it) }
    }

    private fun decode(context: Context, uri: android.net.Uri, mime: String): Bitmap? {
        // The provider's own thumbnail is the cheapest route and also covers video and PDF.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.contentResolver.loadThumbnail(uri, Size(EDGE, EDGE), null)
            }
            DocumentsContract.getDocumentThumbnail(context.contentResolver, uri, android.graphics.Point(EDGE, EDGE), null)?.let { return it }
        } catch (_: Throwable) { }
        if (!mime.startsWith("image/")) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= EDGE && bounds.outHeight / (sample * 2) >= EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Throwable) { null }
    }
}
