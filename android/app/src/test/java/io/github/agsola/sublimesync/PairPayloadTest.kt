package io.github.agsola.sublimesync

import io.github.agsola.sublimesync.model.PairPayload
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class PairPayloadTest {
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })

    @Test fun pairPayloadRoundTrip() {
        val p = PairPayload.parse("""{"v":1,"k":"$key","s":"https://sync.example.com","m":"http://100.100.1.1:8788"}""")
        assertEquals("https://sync.example.com", p.server)
        assertEquals("http://100.100.1.1:8788", p.mac)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairPayloadParsingRejectsBadVersion() {
        PairPayload.parse("""{"v":2,"k":"$key","s":"https://sync.example.com","m":"http://100.100.1.1:8788"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPlainHttpServer() {
        PairPayload.parse("""{"v":1,"k":"$key","s":"http://sync.example.com","m":"http://100.100.1.1:8788"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGarbage() {
        PairPayload.parse("hello")
    }
}
