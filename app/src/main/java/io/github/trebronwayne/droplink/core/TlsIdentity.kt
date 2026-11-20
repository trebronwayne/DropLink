package io.github.trebronwayne.droplink.core

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The node's TLS identity: a self-signed RSA certificate created on first use and kept in app-private storage.
 *
 * Browsers will not accept a certificate that has no Subject Alternative Names, so this one lists the phone's own
 * addresses (and the usual hotspot addresses). When the phone joins a network whose address is not listed, the
 * certificate is issued again with the same key and the new address added, so it never needs a click-through for
 * the wrong reason. The key stays the same, so phones that paired with this one keep trusting it ([Material.keyPin]).
 *
 * There is no certificate authority on a local network, so a browser still warns once that the certificate is
 * self-signed. That is expected. Trust comes from comparing the fingerprint, not from a CA signature.
 */
object TlsIdentity {
    const val ALIAS = "droplink"
    private const val FILE = "droplink-tls.p12"
    private const val PREFS = "droplink_tls"
    private const val KEY_PASSWORD = "store_password"
    private const val VALID_DAYS = 397L
    private const val RENEW_BEFORE_DAYS = 45L
    private const val MAX_ADDRESSES = 24
    private const val DAY_MS = 86_400_000L

    /** Gateways Android hands out when it hosts a hotspot or Wi-Fi Direct group, so those work from the first visit. */
    private val USUAL_GATEWAYS = listOf("192.168.43.1", "192.168.49.1", "192.168.137.1")

    class Material(
        val keyStore: KeyStore,
        val password: CharArray,
        /** SHA-256 of the whole certificate, as a browser shows it. Changes whenever the certificate is issued again. */
        val fingerprint: String,
        /** SHA-256 of the public key, in lowercase hex. Stays the same for the life of the identity. */
        val keyPin: String,
        /** Addresses the certificate is valid for. */
        val addresses: Set<String>,
        val notAfter: Long
    )

    @Volatile private var cached: Material? = null

    /** This phone's private IPv4 addresses right now, on whatever networks it is connected to. */
    fun currentAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun load(context: Context): Material {
        val addresses = currentAddresses()
        cached?.let { if (covers(it, addresses)) return it }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = File(context.noBackupFilesDir, FILE)
        val saved = prefs.getString(KEY_PASSWORD, null)
        if (saved != null && file.exists()) {
            val store = try {
                KeyStore.getInstance("PKCS12").also { k -> file.inputStream().use { k.load(it, saved.toCharArray()) } }
            } catch (_: Exception) {
                null // Unreadable keystore: fall through and create a fresh identity.
            }
            if (store != null) {
                val material = material(store, saved)
                if (covers(material, addresses)) return material
                // Same key, new certificate: the address list grew, or the old certificate has no names or is running out.
                return try {
                    val certificate = store.getCertificate(ALIAS) as X509Certificate
                    val key = store.getKey(ALIAS, saved.toCharArray()) as PrivateKey
                    issue(file, prefs, saved, KeyPair(certificate.publicKey, key), material.addresses, addresses)
                } catch (e: Exception) {
                    CrashLog.record("Security certificate could not be renewed; keeping the current one", e)
                    material
                }
            }
        }
        val password = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return issue(file, prefs, password, keyPair, emptySet(), addresses)
    }

    /**
     * Called now and then while hosting. Returns the new identity when the phone moved to an address the current
     * certificate does not cover, so the HTTPS front end can switch to it, or null when nothing changed.
     */
    @Synchronized
    fun refresh(context: Context): Material? {
        val have = cached ?: return null
        if (covers(have, currentAddresses())) return null
        val fresh = load(context)
        return if (fresh !== have) fresh else null
    }

    private fun covers(material: Material, addresses: List<String>): Boolean =
        material.addresses.isNotEmpty() &&
            addresses.all { it in material.addresses } &&
            material.notAfter > System.currentTimeMillis() + RENEW_BEFORE_DAYS * DAY_MS

    private fun issue(
        file: File, prefs: android.content.SharedPreferences, password: String, keyPair: KeyPair,
        previous: Set<String>, current: List<String>
    ): Material {
        // Newest addresses first, so they are never the ones dropped when the list is full.
        val addresses = (current + USUAL_GATEWAYS + previous).distinct().take(MAX_ADDRESSES)
        val name = X500Name("CN=DropLink Node,O=DropLink")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()).add(BigInteger.ONE),
            Date(now - DAY_MS), Date(now + VALID_DAYS * DAY_MS), name, keyPair.public
        )
        val names = ArrayList<GeneralName>()
        names += GeneralName(GeneralName.dNSName, "localhost")
        names += GeneralName(GeneralName.dNSName, "droplink.local")
        addresses.forEach { names += GeneralName(GeneralName.iPAddress, it) }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        builder.addExtension(Extension.subjectKeyIdentifier, false, JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.public))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry(ALIAS, keyPair.private, password.toCharArray(), arrayOf(certificate))
        val temp = File(file.parentFile, "$FILE.tmp")
        temp.outputStream().use { store.store(it, password.toCharArray()) }
        if (!temp.renameTo(file)) {
            file.outputStream().use { store.store(it, password.toCharArray()) }
            temp.delete()
        }
        prefs.edit().putString(KEY_PASSWORD, password).apply()
        return material(store, password)
    }

    private fun material(store: KeyStore, password: String): Material {
        val certificate = store.getCertificate(ALIAS) as X509Certificate
        val hash = MessageDigest.getInstance("SHA-256")
        val fingerprint = hash.digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
        val keyPin = hash.digest(certificate.publicKey.encoded).joinToString("") { "%02x".format(it) }
        val addresses = try {
            certificate.subjectAlternativeNames.orEmpty()
                .filter { (it[0] as? Int) == 7 }
                .mapNotNull { it[1] as? String }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
        return Material(store, password.toCharArray(), fingerprint, keyPin, addresses, certificate.notAfter.time).also { cached = it }
    }
}
