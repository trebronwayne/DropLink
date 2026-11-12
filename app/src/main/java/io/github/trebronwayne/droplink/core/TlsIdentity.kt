package io.github.trebronwayne.droplink.core

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The node's TLS identity: a self-signed RSA certificate created on first use and kept in app-private storage.
 * Its SHA-256 fingerprint is what people (and, later, DropLink clients) verify. There is no certificate authority
 * on a local network, so trust comes from comparing this fingerprint, not from a CA signature.
 */
object TlsIdentity {
    const val ALIAS = "droplink"
    private const val FILE = "droplink-tls.p12"
    private const val PREFS = "droplink_tls"
    private const val KEY_PASSWORD = "store_password"

    class Material(val keyStore: KeyStore, val password: CharArray, val fingerprint: String)

    @Synchronized
    fun load(context: Context): Material {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = File(context.noBackupFilesDir, FILE)
        val saved = prefs.getString(KEY_PASSWORD, null)
        if (saved != null && file.exists()) {
            try {
                val store = KeyStore.getInstance("PKCS12")
                file.inputStream().use { store.load(it, saved.toCharArray()) }
                return material(store, saved)
            } catch (_: Exception) {
                // Unreadable keystore: fall through and create a fresh identity.
            }
        }
        val password = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=DropLink Node")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()).add(BigInteger.ONE),
            Date(now - 86_400_000L), Date(now + 5L * 365 * 86_400_000L), name, keyPair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        store.setKeyEntry(ALIAS, keyPair.private, password.toCharArray(), arrayOf(certificate))

        file.outputStream().use { store.store(it, password.toCharArray()) }
        prefs.edit().putString(KEY_PASSWORD, password).apply()
        return material(store, password)
    }

    private fun material(store: KeyStore, password: String): Material {
        val certificate = store.getCertificate(ALIAS) as X509Certificate
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }
        return Material(store, password.toCharArray(), fingerprint)
    }
}
