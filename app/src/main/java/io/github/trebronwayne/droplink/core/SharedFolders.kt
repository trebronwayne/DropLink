package io.github.trebronwayne.droplink.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

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
    }

    fun root(context: Context, id: String): DocumentFile? =
        list(context).firstOrNull { it.id == id }?.let { DocumentFile.fromTreeUri(context, it.uri) }

    /**
     * Resolves a relative path by looking up each segment by name among the real children.
     * Because lookup is name-based, "..", absolute paths and other traversal tricks cannot escape the shared folder.
     */
    fun resolve(context: Context, shareId: String, path: String): DocumentFile? {
        var current = root(context, shareId) ?: return null
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            if (segment == "." || segment == ".." || segment.contains('\\')) return null
            current = current.findFile(segment) ?: return null
        }
        return current
    }
}
