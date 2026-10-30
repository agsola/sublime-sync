package io.github.agsola.sublimesync

import io.github.agsola.sublimesync.crypto.AuthFailedException
import io.github.agsola.sublimesync.crypto.Envelope
import io.github.agsola.sublimesync.crypto.KeyMismatchException
import io.github.agsola.sublimesync.crypto.Keys
import io.github.agsola.sublimesync.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class CryptoTest {
    private val v = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("vectors.json")!!.readText()
    ).jsonObject

    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private val master = ByteArray(32) { it.toByte() }
    private val keys = Keys.derive(master)
    private val env = Base64.getDecoder().decode(s("envelope"))

    @Test fun vectorsDeriveSameKeys() {
        assertEquals(s("keyId"), keys.id)
        assertEquals(s("content"), keys.content.toHex())
        assertEquals(s("blobIdKey"), keys.blobId.toHex())
        assertEquals(s("serverAuth"), keys.serverAuth.toHex())
        assertEquals(s("macAuth"), keys.macAuth.toHex())
        assertEquals(s("serverToken"), keys.bearerServer())
    }

    @Test fun vectorsOpenEnvelope() {
        val plain = Envelope.open(mapOf(keys.id to keys), s("aad"), env)
        assertEquals("{\"hello\":\"mundo ñ\"}", String(plain))
    }

    @Test(expected = AuthFailedException::class)
    fun wrongAadThrowsAuthFailed() {
        Envelope.open(mapOf(keys.id to keys), "blob:other", env)
    }

    @Test(expected = KeyMismatchException::class)
    fun unknownKeyIdThrowsKeyMismatch() {
        val other = Keys.derive(ByteArray(32) { 7 })
        Envelope.open(mapOf(other.id to other), s("aad"), env)
    }
}
