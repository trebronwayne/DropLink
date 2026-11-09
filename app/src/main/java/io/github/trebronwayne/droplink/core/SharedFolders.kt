package io.github.trebronwayne.droplink.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class SharedFolder(val id: String, val name: String, val uri: Uri)

/**
 * Folders the user explicitly chose with the system folder picker (Storage Access Framework).
 * Nothing outside these folders is ever reachable, and access is read-only.
 */
object SharedFolders {
    private const val PREFS = "droplink_shares"
    private const val KEY = "tree_uris"

    private fun idFor(uri: Uri): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }

    private fun stored(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()

    private fun save(context: Context, uris: Set<String>) {
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
        fileCache.clear()
    }

    private class CachedFile(val file: DocumentFile, val storedAt: Long)

    // Looking up a path in the Storage Access Framework costs a system query per folder level, which is slow
    // when many clients are active. Recently seen paths are cached briefly so repeat requests skip those queries.
    private val fileCache = ConcurrentHashMap<String, CachedFile>()
    private const val CACHE_TTL_MS = 30_000L
    private const val CACHE_MAX_ENTRIES = 5_000

    private fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    private fun cacheKey(shareId: String, segments: List<String>): String = shareId + "|" + segments.joinToString("/")

    /** Remembers a child found while listing a folder, so a later download of it needs no lookup. */
    fun remember(shareId: String, parentPath: String, file: DocumentFile) {
        val name = file.name ?: return
        if (fileCache.size > CACHE_MAX_ENTRIES) fileCache.clear()
        fileCache[cacheKey(shareId, segmentsOf(parentPath) + name)] = CachedFile(file, System.currentTimeMillis())
    }

    fun root(context: Context, id: String): DocumentFile? {
        for (text in stored(context)) {
            val uri = Uri.parse(text)
            if (idFor(uri) == id) return DocumentFile.fromTreeUri(context, uri)
        }
        return null
    }

    /**
     * Resolves a relative path by looking up each segment by name among the real children.
     * Because lookup is name-based, "..", absolute paths and other traversal tricks cannot escape the shared folder.
     */
    fun resolve(context: Context, shareId: String, path: String): DocumentFile? {
        val segments = segmentsOf(path)
        for (segment in segments) {
            if (segment == "." || segment == ".." || segment.contains('\\')) return null
        }
        val now = System.currentTimeMillis()
        var found: DocumentFile? = null
        var matched = 0
        var depth = segments.size
        while (depth > 0) {
            val hit = fileCache[cacheKey(shareId, segments.subList(0, depth))]
            if (hit != null && now - hit.storedAt < CACHE_TTL_MS) {
                found = hit.file
                matched = depth
                break
            }
            depth--
        }
        var current: DocumentFile = found ?: (root(context, shareId) ?: return null)
        for (i in matched until segments.size) {
            current = current.findFile(segments[i]) ?: return null
            fileCache[cacheKey(shareId, segments.subList(0, i + 1))] = CachedFile(current, now)
        }
        return current
    }
}
