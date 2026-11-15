package io.github.trebronwayne.droplink.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Locale

/** A lyrics or subtitle file that belongs to a song or video. Exactly one of [uri] and [file] is set. */
data class Sidecar(val name: String, val uri: Uri?, val file: File?) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val base: String get() = name.substringBeforeLast('.', name)
}

/**
 * Finds the lyrics and subtitle files that go with a song or video: same name, next to it, such as
 * "Song.mp3" and "Song.lrc", or "Movie.mkv" and "Movie.en.srt".
 *
 * Android only lets an app read other apps' non-media files (.lrc, .srt) in folders the person has opened to it, so
 * the lookup tries, in order: a file the person picked by hand for this item, the folder next to the file when
 * Android allows it, the folders the person added in Settings (lyrics and subtitle folders), and files DropLink
 * itself received. Lyrics inside the audio file are handled separately.
 */
object Sidecars {
    private const val PREFS = "droplink_player"
    private const val FOLDERS = "text_folders"
    private const val MAX_INDEXED = 60_000
    private const val MAX_DEPTH = 8

    @Volatile private var prefs: android.content.SharedPreferences? = null
    private val _folders = MutableStateFlow<List<String>>(emptyList())
    @Volatile private var index: List<Sidecar>? = null

    /** The folders the person opened for lyrics and subtitles, as tree addresses. */
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _folders.value = try {
            val a = JSONArray(p.getString(FOLDERS, "[]"))
            List(a.length()) { a.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveFolders(list: List<String>) {
        _folders.value = list
        index = null
        prefs?.edit()?.putString(FOLDERS, JSONArray(list).toString())?.apply()
    }

    fun addFolder(context: Context, tree: Uri) {
        try { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        val s = tree.toString()
        if (s !in _folders.value) saveFolders(_folders.value + s)
    }

    fun removeFolder(context: Context, tree: String) {
        try { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        saveFolders(_folders.value - tree)
    }

    /** A readable name for a folder address, for example "Music/Lyrics". */
    fun label(tree: String): String = try {
        Uri.parse(tree).lastPathSegment?.substringAfter(':')?.ifBlank { null } ?: "Folder"
    } catch (_: Exception) { "Folder" }

    /** Forces the next lookup to read the folders again (after the person added files to them). */
    fun rescan() { index = null }

    fun setManual(context: Context, id: String, uri: Uri) {
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        prefs?.edit()?.putString("manual:$id", uri.toString())?.apply()
    }

    private fun manual(context: Context, id: String): Sidecar? {
        val s = prefs?.getString("manual:$id", null) ?: return null
        val uri = Uri.parse(s)
        val name = try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null } ?: (uri.lastPathSegment ?: "lyrics.txt")
        return Sidecar(name, uri, null)
    }

    private fun norm(s: String) = buildString { s.lowercase().forEach { if (it.isLetterOrDigit()) append(it) } }

    /** Does a lyrics or subtitle file with this name belong to this song or video? */
    fun belongs(entry: MediaEntry, candidateBase: String): Boolean {
        val a = norm(entry.baseName)
        val c = norm(candidateBase)
        if (a.isEmpty() || c.isEmpty()) return false
        if (c == a) return true
        // "Movie.en.srt" or "Movie.eng.forced.srt": the song's name followed by a dot and a language
        if (candidateBase.lowercase().startsWith(entry.baseName.lowercase() + ".")) return true
        if (c.startsWith(a) && c.length - a.length <= 4) return true
        // "Artist - Title.lrc" next to "Title.mp3", or the other way round
        if (minOf(a.length, c.length) >= 5 && (c.endsWith(a) || a.endsWith(c))) return true
        val t = norm(entry.title)
        return t.length >= 5 && c == t
    }

    suspend fun find(context: Context, entry: MediaEntry): List<Sidecar> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val found = LinkedHashMap<String, Sidecar>()
        fun add(s: Sidecar) { if (s.ext in TextParsers.SIDECAR_EXT && belongs(entry, s.base)) found.putIfAbsent(s.name.lowercase(), s) }

        manual(app, entry.id)?.let { found["manual"] = it }

        // 1. The folder the file is in, if Android lets us look (older phones, or files this app owns).
        try {
            if (entry.path.isNotBlank()) File(entry.path).parentFile?.listFiles()?.forEach { if (it.isFile && it.canRead()) add(Sidecar(it.name, null, it)) }
        } catch (_: Exception) { }

        // 2. Files Android's media database knows about next to it (it knows the ones this app saved, and on some phones more).
        try {
            val files = MediaStore.Files.getContentUri("external")
            val cols = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME)
            val stem = entry.baseName.take(60).replace("%", "").replace("_", "")
            val (where, args) = if (Build.VERSION.SDK_INT >= 29 && entry.folder.isNotBlank())
                "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" to arrayOf(entry.folder, "%$stem%")
            else "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" to arrayOf("%$stem%")
            app.contentResolver.query(files, cols, where, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    add(Sidecar(name, ContentUris.withAppendedId(files, c.getLong(0)), null))
                }
            }
        } catch (_: Exception) { }

        // 3. The folders the person opened in Settings.
        if (_folders.value.isNotEmpty()) indexed(app).forEach { add(it) }
        found.values.toList()
    }

    private fun indexed(context: Context): List<Sidecar> {
        index?.let { return it }
        val out = ArrayList<Sidecar>()
        for (tree in _folders.value) {
            try {
                val root = Uri.parse(tree)
                walk(context, root, DocumentsContract.getTreeDocumentId(root), 0, out)
            } catch (_: Exception) { }
        }
        index = out
        return out
    }

    private fun walk(context: Context, tree: Uri, docId: String, depth: Int, out: MutableList<Sidecar>) {
        if (depth > MAX_DEPTH || out.size >= MAX_INDEXED) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val folders = ArrayList<String>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) folders += id
                else if (name.substringAfterLast('.', "").lowercase() in TextParsers.SIDECAR_EXT) {
                    out += Sidecar(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), null)
                }
            }
        }
        folders.forEach { walk(context, tree, it, depth + 1, out) }
    }

    fun readBytes(context: Context, s: Sidecar, max: Int = 3 * 1024 * 1024): ByteArray? = try {
        val stream = if (s.file != null) s.file.inputStream() else context.contentResolver.openInputStream(s.uri!!)
        stream?.use { it.readNBytesCompat(max) }
    } catch (_: Exception) { null }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** "English" for "Movie.en.srt", otherwise the file name. */
    fun trackName(entry: MediaEntry, s: Sidecar): String {
        val tail = s.base.substring(minOf(entry.baseName.length, s.base.length)).trim('.', ' ', '-', '_')
        val code = tail.substringBefore('.').lowercase()
        if (code.matches(Regex("[a-z]{2,3}"))) {
            val lang = Locale(code).getDisplayLanguage(Locale.getDefault())
            if (lang.isNotBlank() && lang.lowercase() != code) return lang.replaceFirstChar { it.uppercase() }
        }
        return s.name
    }
}
