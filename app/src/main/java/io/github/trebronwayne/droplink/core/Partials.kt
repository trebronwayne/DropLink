package io.github.trebronwayne.droplink.core

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Half-received files. While a device is sending a file the bytes land here, in the app's private storage, so a
 * dropped connection never loses what already arrived: the device asks how much the phone has and continues from
 * there. A partial file is matched by device and a fingerprint of the file (name, size, date), so it can even be
 * resumed in a brand-new request. Partials left alone for two days are deleted.
 */
object Partials {
    private const val KEEP_MS = 48L * 3600 * 1000
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        val d = File(context.applicationContext.filesDir, "incoming")
        d.mkdirs()
        dir = d
        val limit = System.currentTimeMillis() - KEEP_MS
        d.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    /** The file this upload is being written to. Not created until something is written. */
    fun file(deviceId: String, fingerprint: String, requestId: String, index: Int): File? {
        val d = dir ?: return null
        val name = if (fingerprint.isNotBlank()) {
            val hash = MessageDigest.getInstance("SHA-256").digest("$deviceId|$fingerprint".toByteArray())
            hash.joinToString("") { "%02x".format(it) }.take(40)
        } else {
            "r-$requestId-$index"
        }
        return File(d, "$name.part")
    }

    /** How many bytes of a file of [size] have already arrived. A partial that is larger than the file is discarded. */
    fun length(file: File?, size: Long): Long {
        if (file == null || !file.exists()) return 0L
        val n = file.length()
        if (n > size) {
            file.delete()
            return 0L
        }
        return n
    }
}
