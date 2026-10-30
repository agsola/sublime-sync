package io.github.agsola.sublimesync.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.agsola.sublimesync.crypto.Keys
import io.github.agsola.sublimesync.model.PairPayload
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Pairing(val server: String, val mac: String, val currentKeyId: String)

/**
 * Stores master keys wrapped by an Android Keystore key that can only be used
 * for a few minutes after the user authenticates (fingerprint or device PIN).
 * Old keys are kept so history sealed before a re-pair stays readable.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun pairing(): Pairing? {
        val id = prefs.getString("current", null) ?: return null
        return Pairing(prefs.getString("server", "")!!, prefs.getString("mac", "")!!, id)
    }

    /** Requires a recent authentication (see [AUTH_VALIDITY_SECONDS]). */
    fun store(p: PairPayload) {
        val master = Base64.getUrlDecoder().decode(p.key)
        val id = Keys.keyId(master)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val wrapped = cipher.iv + cipher.doFinal(master)
        master.fill(0)
        val ids = (prefs.getStringSet("ids", emptySet())!! + id)
        prefs.edit()
            .putString("key.$id", Base64.getEncoder().encodeToString(wrapped))
            .putStringSet("ids", ids)
            .putString("current", id)
            .putString("server", p.server.trimEnd('/'))
            .putString("mac", p.mac.trimEnd('/'))
            .apply()
    }

    /** Returns the keyring; throws UserNotAuthenticatedException if auth expired. */
    fun unlock(): Map<String, Keys> {
        val key = wrappingKey()
        return prefs.getStringSet("ids", emptySet())!!.mapNotNull { id ->
            val wrapped = Base64.getDecoder().decode(prefs.getString("key.$id", null) ?: return@mapNotNull null)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
            val master = cipher.doFinal(wrapped, 12, wrapped.size - 12)
            val k = Keys.derive(master)
            master.fill(0)
            k.id to k
        }.toMap()
    }

    fun reset() {
        prefs.edit().clear().apply()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
                .setInvalidatedByBiometricEnrollment(false)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val AUTH_VALIDITY_SECONDS = 300
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "sublime-sync-wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
