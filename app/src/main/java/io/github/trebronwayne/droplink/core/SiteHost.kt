package io.github.trebronwayne.droplink.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * "Host a page": the owner picks a folder that contains a website (index.html, CSS, JS, images). DropLink serves it at
 * /site/ and gives its pages a tiny built-in API: sign in with accounts the owner creates, and send information
 * (form submissions) back to the phone. Everything stays on the phone.
 */
object SiteHost {
    private const val PREFS = "droplink_site"
    private const val SUBMISSIONS = "site_submissions.jsonl"
    private const val MAX_SUBMISSION_BYTES = 5L * 1024 * 1024
    private const val SESSION_MS = 12L * 60 * 60 * 1000
    private val random = SecureRandom()
    const val LOCKED = "\u0000locked"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- settings ------------------------------------------------------------------------------------------------

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false) && folderUri(context) != null
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("enabled", value).apply()

    /** True: anyone on the network can open the page. False: only devices the owner has approved. */
    fun openToAnyone(context: Context) = prefs(context).getBoolean("open", true)
    fun setOpenToAnyone(context: Context, value: Boolean) = prefs(context).edit().putBoolean("open", value).apply()

    fun folderUri(context: Context): Uri? = prefs(context).getString("folder", null)?.let { Uri.parse(it) }

    fun siteId(context: Context): String? = folderUri(context)?.let { SharedFolders.idFor(it) }

    fun folderName(context: Context): String? {
        val uri = folderUri(context) ?: return null
        return androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
    }

    fun setFolder(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clearFolder(context)
        prefs(context).edit().putString("folder", uri.toString()).apply()
    }

    fun clearFolder(context: Context) {
        folderUri(context)?.let {
            // Keep the permission if the same folder is also shared as a normal shared folder.
            val stillShared = SharedFolders.list(context).any { s -> s.uri == it }
            if (!stillShared) try { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        }
        prefs(context).edit().remove("folder").putBoolean("enabled", false).apply()
    }

    // ---- accounts ------------------------------------------------------------------------------------------------

    private fun hash(password: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, 120_000, 256)).encoded

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun usersJson(context: Context) = try { JSONArray(prefs(context).getString("users", "[]")) } catch (_: Exception) { JSONArray() }

    fun users(context: Context): List<String> =
        usersJson(context).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }.sortedBy { it.lowercase() }

    /** Returns false if the name is empty, taken, or the password is shorter than 6 characters. Passwords are stored only as salted hashes. */
    @Synchronized
    fun addUser(context: Context, name: String, password: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 40 || clean.any { it.isISOControl() } || password.length < 6) return false
        val list = usersJson(context)
        if ((0 until list.length()).any { list.getJSONObject(it).getString("name").equals(clean, ignoreCase = true) }) return false
        val salt = ByteArray(16).also { random.nextBytes(it) }
        list.put(JSONObject().put("name", clean).put("salt", hex(salt)).put("hash", hex(hash(password.toCharArray(), salt))))
        prefs(context).edit().putString("users", list.toString()).apply()
        return true
    }

    @Synchronized
    fun removeUser(context: Context, name: String) {
        val list = usersJson(context)
        val kept = JSONArray()
        for (i in 0 until list.length()) if (!list.getJSONObject(i).getString("name").equals(name, ignoreCase = true)) kept.put(list.getJSONObject(i))
        prefs(context).edit().putString("users", kept.toString()).apply()
        sessions.entries.removeIf { it.value.first.equals(name, ignoreCase = true) }
    }

    private class Failures(var count: Int, var lockedUntil: Long)
    private val failures = ConcurrentHashMap<String, Failures>()

    /** null = wrong name or password, [LOCKED] = too many attempts, otherwise the account name. */
    fun verify(context: Context, name: String, password: String): String? {
        val key = name.trim().lowercase()
        val now = System.currentTimeMillis()
        val f = failures.getOrPut(key) { Failures(0, 0) }
        if (f.lockedUntil > now) return LOCKED
        val list = usersJson(context)
        for (i in 0 until list.length()) {
            val u = list.getJSONObject(i)
            if (u.getString("name").lowercase() == key) {
                val ok = MessageDigest.isEqual(hash(password.toCharArray(), unhex(u.getString("salt"))), unhex(u.getString("hash")))
                if (ok) { failures.remove(key); return u.getString("name") }
                break
            }
        }
        if (++f.count >= 5) { f.count = 0; f.lockedUntil = now + 60_000 }
        return null
    }

    // ---- sessions (memory only: signing everyone out when hosting stops is intended) --------------------------------

    private val sessions = ConcurrentHashMap<String, Pair<String, Long>>()

    fun startSession(user: String): String {
        val token = hex(ByteArray(32).also { random.nextBytes(it) })
        if (sessions.size > 500) sessions.entries.removeIf { it.value.second < System.currentTimeMillis() }
        sessions[token] = user to System.currentTimeMillis() + SESSION_MS
        return token
    }

    fun userFor(token: String?): String? {
        val s = token?.let { sessions[it] } ?: return null
        if (s.second < System.currentTimeMillis()) { sessions.remove(token); return null }
        return s.first
    }

    fun endSession(token: String?) { if (token != null) sessions.remove(token) }
    fun endAllSessions() = sessions.clear()

    // ---- information sent from pages to the phone ------------------------------------------------------------------

    private val recent = java.util.ArrayDeque<Long>()

    private fun file(context: Context) = File(context.filesDir, SUBMISSIONS)

    /** false = storage full or too many per minute. */
    @Synchronized
    fun submit(context: Context, form: String, fields: Map<String, String>, user: String?): Boolean {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && recent.first() < now - 60_000) recent.removeFirst()
        if (recent.size >= 120) return false
        val f = file(context)
        if (f.exists() && f.length() > MAX_SUBMISSION_BYTES) return false
        val entry = JSONObject().put("time", now).put("form", form).put("fields", JSONObject(fields))
        if (user != null) entry.put("user", user)
        f.appendText(entry.toString() + "\n")
        recent.addLast(now)
        return true
    }

    fun submissionCount(context: Context): Int = try { file(context).useLines { it.count() } } catch (_: Exception) { 0 }

    /** The newest entries as a JSON array text, for the owner to copy. */
    fun submissionsJson(context: Context, limit: Int = 500): String {
        val out = JSONArray()
        try { file(context).readLines().takeLast(limit).forEach { out.put(JSONObject(it)) } } catch (_: Exception) { }
        return out.toString(2)
    }

    fun clearSubmissions(context: Context) { try { file(context).delete() } catch (_: Exception) { } }
}
