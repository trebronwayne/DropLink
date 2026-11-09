package io.github.trebronwayne.droplink.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class SharedFolder(val id: String, val name: String, val uri: Uri)

/** One entry of a folder, read with a single provider query instead of one query per property. */
data class DocEntry(val docId: String, val name: String, val isDir: Boolean, val size: Long, val mime: String, val modified: Long, val uri: Uri)

/**
 * Folders the user explicitly chose with the system folder picker (Storage Access Framework).
 * Nothing outside these folders is ever reachable, and access is read-only.
 *
 * Speed: DocumentFile.listFiles() followed by name/length/type calls costs one provider query PER PROPERTY PER FILE.
 * Here a folder is read with ONE query that returns every column, and results are cached, so folders open at once.
 */
object SharedFolders {
    private const val PREFS = "droplink_shares"
    private const val KEY = "tree_uris"

    private fun idFor(uri: Uri): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }

    @Volatile private var storedCache: Set<String>? = null

    private fun stored(context: Context): Set<String> =
        storedCache ?: (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()).also { storedCache = it }

    private fun save(context: Context, uris: Set<String>) {
        storedCache = uris
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, HashSet(uris)).apply()
    }

    fun list(context: Context): List<SharedFolder> =
        stored(context).mapNotNull { text ->
            val uri = Uri.parse(text)
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc != null && doc.exists()) SharedFolder(idFor(uri), doc.name ?: "Folder", uri) else null
        }.sortedBy { it.name.lowercase() }

    fun add(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        save(context, stored(context) + uri.toString())
    }

    fun remove(context: Context, folder: SharedFolder) {
        try {
            context.contentResolver.releasePersistableUriPermission(folder.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        save(context, stored(context) - folder.uri.toString())
        dirCache.clear()
        docIdCache.clear()
    }

    // ---- caches -------------------------------------------------------------------------------------------------

    private class CachedDir(val entries: List<DocEntry>, val storedAt: Long)

    private val dirCache = ConcurrentHashMap<String, CachedDir>()      // "share|path" -> listing
    private val docIdCache = ConcurrentHashMap<String, DocEntry>()      // "share|path/to/file" -> entry
    private const val DIR_TTL_MS = 20_000L
    private const val MAX_ENTRIES = 20_000

    private fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
    private fun key(shareId: String, segments: List<String>) = shareId + "|" + segments.joinToString("/")

    private fun treeUri(context: Context, shareId: String): Uri? =
        stored(context).map { Uri.parse(it) }.firstOrNull { idFor(it) == shareId }

    fun root(context: Context, id: String): DocumentFile? = treeUri(context, id)?.let { DocumentFile.fromTreeUri(context, it) }

    private fun validSegments(path: String): List<String>? {
        val segments = segmentsOf(path)
        for (s in segments) if (s == "." || s == ".." || s.contains('\\')) return null
        return segments
    }

    /** Reads all children of a directory document with ONE query. */
    private fun queryChildren(context: Context, tree: Uri, parentDocId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<DocEntry>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                out += DocEntry(
                    docId = id, name = name, isDir = isDir,
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    mime = if (isDir) "" else mime,
                    modified = if (c.isNull(4)) 0L else c.getLong(4),
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                )
            }
        }
        return out
    }

    /** Lists a folder. Cached for a short time; hot listings are served instantly. Returns null if the path is not a folder. */
    fun listDir(context: Context, shareId: String, path: String, fresh: Boolean = false): List<DocEntry>? {
        val segments = validSegments(path) ?: return null
        val tree = treeUri(context, shareId) ?: return null
        val k = key(shareId, segments)
        val now = System.currentTimeMillis()
        if (!fresh) dirCache[k]?.let { if (now - it.storedAt < DIR_TTL_MS) return it.entries }

        val parentDocId = if (segments.isEmpty()) DocumentsContract.getTreeDocumentId(tree) else {
            val dir = lookup(context, shareId, tree, segments) ?: return null
            if (!dir.isDir) return null
            dir.docId
        }
        val entries = try { queryChildren(context, tree, parentDocId) } catch (_: Exception) { return null }
        val sorted = entries.sortedWith(compareByDescending<DocEntry> { it.isDir }.thenBy { it.name.lowercase() })
        if (dirCache.size > 2_000) dirCache.clear()
        if (docIdCache.size > MAX_ENTRIES) docIdCache.clear()
        dirCache[k] = CachedDir(sorted, now)
        sorted.forEach { docIdCache[key(shareId, segments + it.name)] = it }
        return sorted
    }

    /** Finds one entry by walking names, one single-query listing per uncached level. Name based, so ".." cannot escape. */
    private fun lookup(context: Context, shareId: String, tree: Uri, segments: List<String>): DocEntry? {
        docIdCache[key(shareId, segments)]?.let { return it }
        var depth = segments.size - 1
        // Start from the deepest cached ancestor.
        var current: DocEntry? = null
        var matched = 0
        while (depth > 0) {
            val hit = docIdCache[key(shareId, segments.subList(0, depth))]
            if (hit != null) { current = hit; matched = depth; break }
            depth--
        }
        var parentId = current?.docId ?: DocumentsContract.getTreeDocumentId(tree)
        for (i in matched until segments.size) {
            val children = try { queryChildren(context, tree, parentId) } catch (_: Exception) { return null }
            val prefix = segments.subList(0, i)
            children.forEach { docIdCache[key(shareId, prefix + it.name)] = it }
            val next = children.firstOrNull { it.name == segments[i] } ?: return null
            if (i < segments.size - 1 && !next.isDir) return null
            parentId = next.docId
            current = next
        }
        return current
    }

    /** Resolves a file or folder entry (no per-property provider queries). */
    fun entry(context: Context, shareId: String, path: String): DocEntry? {
        val segments = validSegments(path) ?: return null
        if (segments.isEmpty()) return null
        val tree = treeUri(context, shareId) ?: return null
        return lookup(context, shareId, tree, segments)
    }

    /** Kept for callers that still need a DocumentFile. */
    fun resolve(context: Context, shareId: String, path: String): DocumentFile? {
        val e = entry(context, shareId, path) ?: return if (segmentsOf(path).isEmpty()) root(context, shareId) else null
        return if (e.isDir) DocumentFile.fromTreeUri(context, e.uri) else DocumentFile.fromSingleUri(context, e.uri)
    }
}
