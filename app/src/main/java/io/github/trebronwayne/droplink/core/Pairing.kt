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

/**
 * A device asking to connect. [deviceName] is what the browser reports ("Chrome on Android"); [person] and [model] are
 * typed by the visitor, because browsers hide the real phone model, so the owner can tell devices apart.
 */
data class PairRequest(
    val id: String,
    val deviceName: String,
    val code: String,
    val createdAt: Long,
    val status: PairStatus,
    val person: String = "",
    val model: String = "",
    val platform: String = ""
) {
    /** The best name to show: who it is if they said, otherwise what the browser reported. */
    val label: String get() = person.ifBlank { deviceName }
}

/** What the phone does when a paired device wants to send it files. */
enum class UploadPolicy(val key: String) {
    /** Every batch of files needs an answer on the phone: approve once, approve always, or deny. */
    Ask("ask"),
    /** Files from this device are accepted without asking. */
    Always("always"),
    /** This device can never send files. */
    Blocked("blocked");

    companion object {
        fun from(key: String?): UploadPolicy = values().firstOrNull { it.key == key } ?: Ask
    }
}

/** A paired device. Only a hash of its session token is stored, so the file cannot be used to impersonate it. */
data class TrustedDevice(
    val id: String,
    val name: String,
    val tokenHash: String,
    val createdAt: Long,
    val uploads: UploadPolicy = UploadPolicy.Ask,
    /** Who is using it, as typed by them ("Sarah"). Empty if they did not say. */
    val person: String = "",
    /** The exact device model, as typed by them ("Samsung Galaxy A14"). Browsers do not reveal it. */
    val model: String = "",
    /** What the browser reported ("Chrome on Android"). */
    val platform: String = ""
) {
    /** The best name to show: who it is if known, otherwise the browser's description. */
    val label: String get() = person.ifBlank { name }
}

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
                    list += TrustedDevice(
                        o.getString("id"), o.getString("name"), o.getString("hash"), o.getLong("created"),
                        UploadPolicy.from(o.optString("uploads", "ask")),
                        o.optString("person"), o.optString("model"), o.optString("platform")
                    )
                }
            } catch (_: Exception) { }
            setTrusted(list)
        }
    }

    private fun clean(raw: String, max: Int) = raw.filter { !it.isISOControl() }.trim().take(max)

    fun create(rawName: String, rawPerson: String = "", rawModel: String = "", rawPlatform: String = ""): PairRequest? {
        val created: PairRequest
        synchronized(lock) {
            val now = System.currentTimeMillis()
            purge(now)
            while (recentCreations.isNotEmpty() && now - recentCreations.first() > 60_000) recentCreations.removeFirst()
            val pending = _requests.value.count { it.status == PairStatus.Pending }
            if (recentCreations.size >= MAX_PER_MINUTE || pending >= MAX_PENDING) return null
            recentCreations.addLast(now)
            val name = rawName.filter { !it.isISOControl() }.trim().take(40).ifEmpty { "Unknown device" }
            created = PairRequest(
                hex(16), name, "%06d".format(random.nextInt(1_000_000)), now, PairStatus.Pending,
                person = clean(rawPerson, 40), model = clean(rawModel, 60), platform = clean(rawPlatform, 60)
            )
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
    fun claim(id: String): String? {
        synchronized(lock) {
            purge(System.currentTimeMillis())
            val request = _requests.value.firstOrNull { it.id == id && it.status == PairStatus.Approved } ?: return null
            val token = hex(32)
            val list = _trusted.value + TrustedDevice(
                hex(8), request.deviceName, sha256(token), System.currentTimeMillis(),
                person = request.person, model = request.model, platform = request.platform
            )
            setTrusted(list)
            persist(list)
            _requests.value = _requests.value.filter { it.id != id }
            return token
        }
    }

    fun isAuthorized(token: String): Boolean = tokenHashes.contains(sha256(token))

    /** The paired device a session token belongs to, or null if the token is unknown or was revoked. */
    fun deviceFor(token: String): TrustedDevice? {
        val hash = sha256(token)
        return _trusted.value.firstOrNull { it.tokenHash == hash }
    }

    /** Changes what happens when this device sends files. Prefer [Uploads.setPolicy], which also settles waiting requests. */
    fun setUploadPolicy(deviceId: String, policy: UploadPolicy) {
        synchronized(lock) {
            val list = _trusted.value.map { if (it.id == deviceId) it.copy(uploads = policy) else it }
            setTrusted(list)
            persist(list)
        }
    }

    /** A device tells the phone who it is (or fixes a typo). Only the device's own session can call this. */
    fun updateInfo(deviceId: String, rawPerson: String, rawModel: String) {
        synchronized(lock) {
            val list = _trusted.value.map {
                if (it.id == deviceId) it.copy(person = clean(rawPerson, 40), model = clean(rawModel, 60)) else it
            }
            setTrusted(list)
            persist(list)
        }
    }

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
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("hash", it.tokenHash).put("created", it.createdAt)
                    .put("uploads", it.uploads.key).put("person", it.person).put("model", it.model).put("platform", it.platform)
            )
        }
        prefs?.edit()?.putString(KEY, array.toString())?.apply()
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
