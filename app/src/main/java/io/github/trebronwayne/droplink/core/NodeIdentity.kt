package io.github.trebronwayne.droplink.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

/** Device identity backed by a non-exportable Android Keystore signing key. */
object NodeIdentity {
    private const val KEY_ALIAS = "droplink.node.identity.v1"
    private const val PREFS = "droplink_identity"
    private const val NODE_ID_KEY = "node_id"

    fun getOrCreateNodeId(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.getString(NODE_ID_KEY, null)?.let { return it }

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKeyPair()
        }

        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey.encoded
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(publicKey)
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte) }
        val nodeId = "dl-$fingerprint"
        preferences.edit().putString(NODE_ID_KEY, nodeId).apply()
        return nodeId
    }
}
