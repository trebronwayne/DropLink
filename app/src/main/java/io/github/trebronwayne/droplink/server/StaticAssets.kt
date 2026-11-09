package io.github.trebronwayne.droplink.server

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

class Asset(val raw: ByteArray, val gzip: ByteArray?, val etag: String)

/** The web client files are read from the APK once, compressed once, and then served from memory. */
object StaticAssets {
    private val cache = ConcurrentHashMap<String, Asset>()
    private val compressible = listOf(".js", ".css", ".html", ".svg", ".json", ".txt", ".map")

    fun get(context: Context, rel: String): Asset? {
        cache[rel]?.let { return it }
        val raw = try { context.assets.open("web/$rel").use { it.readBytes() } } catch (_: IOException) { return null }
        val gz = if (raw.size > 512 && compressible.any { rel.endsWith(it) }) {
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        } else null
        val tag = MessageDigest.getInstance("MD5").digest(raw).take(8).joinToString("") { "%02x".format(it) }
        return Asset(raw, gz, "\"$tag\"").also { cache[rel] = it }
    }

    /** Loads the entry page and its scripts ahead of the first visitor. */
    fun warm(context: Context) {
        try {
            get(context, "index.html")
            context.assets.list("web/assets")?.forEach { get(context, "assets/$it") }
        } catch (_: Throwable) { }
    }
}
