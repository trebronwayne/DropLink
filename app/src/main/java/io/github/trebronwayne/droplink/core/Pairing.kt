package io.github.trebronwayne.droplink.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

enum class PairStatus { Pending, Approved, Denied, Expired }

data class PairRequest(val id: String, val deviceName: String, val code: String, val createdAt: Long, val status: PairStatus)

/** A paired device. Only a hash of its session token is stored, so the file cannot be used to impersonate it. */
data class TrustedDevice(val id: String, val name: String, val tokenHash: String, val createdAt: Long)

/**
 * Pairing and trust. A new device asks to connect, the phone owner approves it after comparing the confirmation
 * code on both screens, and the device then receives a long-lived session token. Trust can be revoked at any time.
 */
object Pairing {
    private const val PREFS = "droplink_trust"
    private const val KEY = "devices"
    private const val PENDING_TTL_MS = 120_000L
    private const val MAX_PENDING = 3
    private const val MAX_PER_MINUTE = 5

    private val lock = Any()
    private val random = SecureRandom()
    private var prefs: SharedPreferences? = null
    private val recentCreations = ArrayDeque<Long>()
    private val _requests = MutableStateFlow<List<PairRequest>>(emptyList())
    private val _trusted = MutableStateFlow<List<TrustedDevice>>(emptyList())
    @Volatile private var tokenHashes: Set<String> = emptySet()

    val requests: StateFlow<List<PairRequest>> = _requests.asStateFlow()
    val trusted: StateFlow<List<TrustedDevice>> = _trusted.asStateFlow()

    /** Called when a new request arrives, so the service can raise a notification. */
    @Volatile var onRequest: ((PairRequest) -> Unit)? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            val list = mutableListOf<TrustedDevice>()
            try {
                val array = JSONArray(p.getString(KEY, "[]"))
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    list += TrustedDevice(o.getString("id"), o.getString("name"), o.getString("hash"), o.getLong("created"))
                }
            } catch (_: Exception) { }
            setTrusted(list)
        }
    }

    fun create(rawName: String): PairRequest? {
        val created: PairRequest
        synchronized(lock) {
            val now = System.currentTimeMillis()
            purge(now)
            while (recentCreations.isNotEmpty() && now - recentCreations.first() > 60_000) recentCreations.removeFirst()
            val pending = _requests.value.count { it.status == PairStatus.Pending }
            if (recentCreations.size >= MAX_PER_MINUTE || pending >= MAX_PENDING) return null
            recentCreations.addLast(now)
            val name = rawName.filter { !it.isISOControl() }.trim().take(40).ifEmpty { "Unknown device" }
            created = PairRequest(hex(16), name, "%06d".format(random.nextInt(1_000_000)), now, PairStatus.Pending)
            _requests.value = _requests.value + created
        }
        onRequest?.invoke(created)
        return created
    }

    fun statusOf(id: String): PairStatus = synchronized(lock) {
        purge(System.currentTimeMillis())
        _requests.value.firstOrNull { it.id == id }?.status ?: PairStatus.Expired
    }

    fun approve(id: String) = transition(id, PairStatus.Approved)
    fun deny(id: String) = transition(id, PairStatus.Denied)

    /** Turns an approved request into a session token (returned once, never stored in clear). */
    fun claim(id: String): String? = synchronized(lock) {
        purge(System.currentTimeMillis())
        val request = _requests.value.firstOrNull { it.id == id && it.status == PairStatus.Approved } ?: return null
        val token = hex(32)
        val list = _trusted.value + TrustedDevice(hex(8), request.deviceName, sha256(token), System.currentTimeMillis())
        setTrusted(list)
        persist(list)
        _requests.value = _requests.value.filter { it.id != id }
        token
    }

    fun isAuthorized(token: String): Boolean = tokenHashes.contains(sha256(token))

    fun revoke(deviceId: String) {
        synchronized(lock) {
            val list = _trusted.value.filter { it.id != deviceId }
            setTrusted(list)
            persist(list)
        }
    }

    fun clearRequests() {
        synchronized(lock) { _requests.value = emptyList() }
    }

    private fun transition(id: String, to: PairStatus) {
        synchronized(lock) {
            purge(System.currentTimeMillis())
            _requests.value = _requests.value.map {
                if (it.id == id && it.status == PairStatus.Pending) it.copy(status = to) else it
            }
        }
    }

    private fun purge(now: Long) {
        _requests.value = _requests.value.filter {
            val limit = if (it.status == PairStatus.Pending) PENDING_TTL_MS else PENDING_TTL_MS * 2
            now - it.createdAt < limit
        }
    }

    private fun setTrusted(list: List<TrustedDevice>) {
        tokenHashes = list.map { it.tokenHash }.toSet()
        _trusted.value = list
    }

    private fun persist(list: List<TrustedDevice>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("hash", it.tokenHash).put("created", it.createdAt))
        }
        prefs?.edit()?.putString(KEY, array.toString())?.apply()
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
