package io.github.trebronwayne.droplink.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-language timeline of what happened while hosting (screen off, app in the background, Android pausing or
 * stopping the server). It helps find out why hosting stopped without needing a computer.
 */
object EventLog {
    private const val FILE = "events.log"
    private const val MAX_CHARS = 24_000
    @Volatile private var directory: File? = null

    fun init(context: Context) {
        directory = context.applicationContext.filesDir
    }

    @Synchronized
    fun add(message: String) {
        try {
            val file = File(directory ?: return, FILE)
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val existing = if (file.exists()) file.readText() else ""
            file.writeText((existing + "[$stamp] $message\n").takeLast(MAX_CHARS))
        } catch (_: Throwable) {
            // Logging must never cause a problem.
        }
    }

    fun read(context: Context): String {
        return try {
            val file = File(context.applicationContext.filesDir, FILE)
            if (file.exists()) file.readText() else ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun clear(context: Context) {
        try { File(context.applicationContext.filesDir, FILE).delete() } catch (_: Throwable) { }
    }
}
