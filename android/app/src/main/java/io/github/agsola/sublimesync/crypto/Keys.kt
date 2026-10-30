package io.github.agsola.sublimesync.crypto

import java.security.MessageDigest
import java.util.Base64

/** Keys derived from the 32-byte master key; mirrors internal/proto/keys.go. */
class Keys(
    val id: String,
    val content: ByteArray,
    val blobId: ByteArray,
    val serverAuth: ByteArray,
    val macAuth: ByteArray,
) {
    fun bearerServer(): String = token(serverAuth)
    fun bearerMac(): String = token(macAuth)

    companion object {
        fun derive(master: ByteArray): Keys {
            require(master.size == 32) { "master key must be 32 bytes" }
            return Keys(
                id = keyId(master),
                content = Hkdf.sha256(master, "sublime-sync/content"),
                blobId = Hkdf.sha256(master, "sublime-sync/blobid"),
                serverAuth = Hkdf.sha256(master, "sublime-sync/server-auth"),
                macAuth = Hkdf.sha256(master, "sublime-sync/mac-auth"),
            )
        }

        fun keyId(master: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(master).copyOfRange(0, 4).toHex()

        private fun token(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
