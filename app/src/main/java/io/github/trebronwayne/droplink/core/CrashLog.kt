package io.github.trebronwayne.droplink.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a small on-device problem report (caught startup errors and uncaught crashes) so problems can be
 * read and copied from the phone without a computer or logcat.
 */
object CrashLog {
    private const val FILE = "diagnostics.log"
    private const val MAX_CHARS = 48_000
    @Volatile private var directory: File? = null
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        directory = context.applicationContext.filesDir
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("CRASH on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    fun record(title: String, error: Throwable? = null) {
        try {
            val file = File(directory ?: return, FILE)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = StringBuilder("[$stamp] $title\n")
            if (error != null) {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                entry.append(writer.toString().take(6000)).append('\n')
            }
            val existing = if (file.exists()) file.readText() else ""
            file.writeText((existing + entry.toString()).takeLast(MAX_CHARS))
        } catch (_: Throwable) {
            // Never let logging itself cause a problem.
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
