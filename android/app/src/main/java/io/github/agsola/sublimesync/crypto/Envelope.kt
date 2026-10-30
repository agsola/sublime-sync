package io.github.agsola.sublimesync.crypto

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.security.SecureRandom
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
    /** Same layout without Brotli: what the phone writes (it has no Brotli encoder). */
    private const val VERSION_RAW: Byte = 0x02
    private const val HEADER = 1 + 4 + 12
    private const val MAX_PLAINTEXT = 64 shl 20

    fun open(keyring: Map<String, Keys>, aad: String, env: ByteArray): ByteArray {
        if (env.size < HEADER || (env[0] != VERSION && env[0] != VERSION_RAW)) throw FormatException()
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
        if (env[0] == VERSION_RAW) {
            if (compressed.size > MAX_PLAINTEXT) throw FormatException()
            return compressed
        }
        return BrotliInputStream(ByteArrayInputStream(compressed)).use { input ->
            // Text usually expands 4–10×; starting near that size avoids repeated doubling copies.
            val out = java.io.ByteArrayOutputStream(minOf(compressed.size.toLong() * 6, MAX_PLAINTEXT.toLong()).toInt().coerceAtLeast(64 * 1024))
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

    /** Seals without compression (version 2). */
    fun seal(k: Keys, aad: String, plaintext: ByteArray): ByteArray =
        sealWithNonce(k, aad, plaintext, ByteArray(12).also { SecureRandom().nextBytes(it) })

    internal fun sealWithNonce(k: Keys, aad: String, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.content, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray())
        val id = k.id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return byteArrayOf(VERSION_RAW) + id + nonce + cipher.doFinal(plaintext)
    }

    fun blobAad(id: String) = "blob:$id"
    fun commandAad(id: String) = "command:$id"
    fun resultAad(id: String) = "result:$id"
    fun stateAad(name: String) = "state:$name"
    fun draftAad(id: String) = "draft:$id"
    fun manifestAad(seq: Long) = "manifest:$seq"
}
