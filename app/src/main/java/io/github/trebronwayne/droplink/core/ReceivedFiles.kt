package io.github.trebronwayne.droplink.core

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** A file another device sent to this phone. */
data class ReceivedFile(val name: String, val size: Long, val from: String, val at: Long)

/**
 * Where one incoming file is being written. Call [commit] when every byte arrived, or [abort] to throw it away.
 * Nothing becomes visible to other apps until [commit], so a dropped connection never leaves a half file behind.
 */
class ReceiveSink internal constructor(
    val name: String,
    val output: OutputStream,
    private val onCommit: () -> Unit,
    private val onAbort: () -> Unit
) {
    fun commit() = onCommit()
    fun abort() = onAbort()
}

/**
 * Files sent to this phone are saved in the public Downloads folder, inside a "DropLink" folder, so the owner can
 * find them with any file manager. No storage permission is needed: Android lets an app create its own downloads.
 * On Android 8 and 9 (before scoped storage) they go to the app's own Downloads folder instead.
 */
object ReceivedFiles {
    const val FOLDER = "DropLink"
    const val LOCATION = "Downloads/DropLink"
    private const val PREFS = "droplink_received"
    private const val KEY = "recent"
    private const val KEEP = 50
    private const val SPACE_MARGIN = 64L * 1024 * 1024

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private val _recent = MutableStateFlow<List<ReceivedFile>>(emptyList())

    /** Newest first. */
    val recent: StateFlow<List<ReceivedFile>> = _recent.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            val list = ArrayList<ReceivedFile>()
            try {
                val array = JSONArray(p.getString(KEY, "[]"))
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    list += ReceivedFile(o.getString("name"), o.getLong("size"), o.getString("from"), o.getLong("at"))
                }
            } catch (_: Exception) { }
            _recent.value = list
        }
    }

    fun record(file: ReceivedFile) {
        synchronized(lock) {
            val list = (listOf(file) + _recent.value).take(KEEP)
            _recent.value = list
            val array = JSONArray()
            list.forEach { array.put(JSONObject().put("name", it.name).put("size", it.size).put("from", it.from).put("at", it.at)) }
            prefs?.edit()?.putString(KEY, array.toString())?.apply()
        }
    }

    /** Forgets the list. The files themselves stay where they are. */
    fun clear() {
        synchronized(lock) {
            _recent.value = emptyList()
            prefs?.edit()?.remove(KEY)?.apply()
        }
    }

    /** A safe file name: no folders, no control characters, not hidden, not absurdly long. */
    fun cleanName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = name.filter { !it.isISOControl() && it !in "<>:\"|?*" }
        name = name.replace(Regex("\\s+"), " ").trim().trimStart('.')
        if (name.length > 120) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0 && name.length - dot <= 12) name.substring(0, 120 - (name.length - dot)) + name.substring(dot) else name.take(120)
        }
        return name.ifEmpty { "file" }
    }

    /** True if the phone has room for [bytes] plus a safety margin. */
    fun hasSpace(context: Context, bytes: Long): Boolean = try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        StatFs(dir.path).availableBytes >= bytes + SPACE_MARGIN
    } catch (_: Exception) {
        true
    }

    /** Starts saving one file. Returns null if the phone cannot create it. */
    fun open(context: Context, name: String): ReceiveSink? {
        val app = context.applicationContext
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openMediaStore(app, name) else openLegacy(app, name)
        } catch (_: Exception) {
            null
        }
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun openMediaStore(app: Context, name: String): ReceiveSink? {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val out = resolver.openOutputStream(uri)
        if (out == null) {
            resolver.delete(uri, null, null)
            return null
        }
        // Android adds " (1)" when the name is taken, so ask what it actually used.
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return ReceiveSink(
            finalName, out,
            onCommit = {
                try { out.close() } catch (_: Exception) { }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            },
            onAbort = {
                try { out.close() } catch (_: Exception) { }
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            }
        )
    }

    private fun openLegacy(app: Context, name: String): ReceiveSink? {
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = uniqueFile(dir, name)
        val part = File(dir, target.name + ".part")
        val out = FileOutputStream(part)
        return ReceiveSink(
            target.name, out,
            onCommit = {
                out.close()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            },
            onAbort = {
                try { out.close() } catch (_: Exception) { }
                part.delete()
            }
        )
    }

    private fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..9999) {
            val candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "$base (${System.currentTimeMillis()})$ext")
    }
}
