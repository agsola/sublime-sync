package io.github.agsola.sublimesync.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869) with an empty salt, as used by the Go side. */
object Hkdf {
    fun sha256(ikm: ByteArray, info: String, len: Int = 32): ByteArray {
        val prk = hmac(ByteArray(32), ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < len) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
