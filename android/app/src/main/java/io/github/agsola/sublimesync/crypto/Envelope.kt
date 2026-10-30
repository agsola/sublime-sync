package io.github.agsola.sublimesync.crypto

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KeyMismatchException(val keyId: String) : Exception("data encrypted with unknown key $keyId")
class AuthFailedException : Exception("envelope authentication failed")
class FormatException : Exception("malformed envelope")

/**
 * Opens envelopes produced by internal/proto/envelope.go:
 * version(1) | keyId(4) | nonce(12) | AES-256-GCM(brotli(plaintext)).
 */
object Envelope {
    private const val VERSION: Byte = 0x01
    private const val HEADER = 1 + 4 + 12
    private const val MAX_PLAINTEXT = 64 shl 20

    fun open(keyring: Map<String, Keys>, aad: String, env: ByteArray): ByteArray {
        if (env.size < HEADER || env[0] != VERSION) throw FormatException()
        val id = env.copyOfRange(1, 5).toHex()
        val k = keyring[id] ?: throw KeyMismatchException(id)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k.content, "AES"), GCMParameterSpec(128, env, 5, 12))
        cipher.updateAAD(aad.toByteArray())
        val compressed = try {
            cipher.doFinal(env, HEADER, env.size - HEADER)
        } catch (e: AEADBadTagException) {
            throw AuthFailedException()
        }
        return BrotliInputStream(ByteArrayInputStream(compressed)).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_PLAINTEXT) throw FormatException()
            }
            out.toByteArray()
        }
    }

    fun blobAad(id: String) = "blob:$id"
    fun manifestAad(seq: Long) = "manifest:$seq"
}
