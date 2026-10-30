package io.github.agsola.sublimesync

import io.github.agsola.sublimesync.crypto.Keys
import io.github.agsola.sublimesync.data.AuthRejectedException
import io.github.agsola.sublimesync.data.Cache
import io.github.agsola.sublimesync.data.HttpStatusException
import io.github.agsola.sublimesync.data.Origin
import io.github.agsola.sublimesync.data.Repository
import io.github.agsola.sublimesync.data.Source
import io.github.agsola.sublimesync.data.reconnecting
import io.github.agsola.sublimesync.model.Event
import io.github.agsola.sublimesync.model.ListItem
import io.github.agsola.sublimesync.model.SnapshotJson
import io.github.agsola.sublimesync.model.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Base64

class RepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixture = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("fixture.json")!!.readText()
    ).jsonObject
    private val snaps = fixture["snapshots"]!!.jsonArray.map {
        val o = it.jsonObject
        SnapshotJson(o["seq"]!!.jsonPrimitive.long, o["at"]!!.jsonPrimitive.long, o["envelope"]!!.jsonPrimitive.content)
    }
    private val blobs = fixture["blobs"]!!.jsonObject.mapValues { Base64.getDecoder().decode(it.value.jsonPrimitive.content) }
    private val keys = Keys.derive(ByteArray(32) { it.toByte() }).let { mapOf(it.id to it) }

    private inner class Fake(
        var seq: Int = 2,
        var delayMs: Long = 0,
        var fail: Boolean = false,
        var macOnline: Boolean = true,
        var rejected: Boolean = false,
    ) : Source {
        override suspend fun latest(): SnapshotJson {
            delay(delayMs)
            if (rejected) throw HttpStatusException(401)
            if (fail) throw IOException("down")
            return snaps[seq - 1]
        }
        override suspend fun snapshot(seq: Long) = snaps[(seq - 1).toInt()]
        override suspend fun list(before: Long?, limit: Int) = snaps.reversed().map { ListItem(it.seq, it.at) }
        override suspend fun blob(id: String): ByteArray {
            if (fail) throw IOException("down")
            return blobs[id]!!
        }
        override suspend fun status() = Status(macOnline = macOnline)
        override fun events(): Flow<Event> = emptyFlow()
    }

    private fun repo(mac: Source?, server: Source) = Repository(mac, server, Cache(tmp.root), keys)

    @Test fun prefersMacWhenFast() = runTest {
        val r = repo(Fake(delayMs = 100), Fake())
        r.refresh()
        val v = r.state.value.view!!
        assertEquals(Origin.MAC_TAILSCALE, v.origin)
        assertEquals(listOf("uno", "DOS 🙂"), v.tabs.map { it.text })
    }

    @Test fun raceFallsBackWhenMacTimesOut() = runTest {
        val r = repo(Fake(delayMs = 3_000), Fake())
        r.refresh()
        assertEquals(Origin.SERVER_LIVE, r.state.value.view!!.origin)
    }

    @Test fun serverLiveVsCopyFromStatus() = runTest {
        val r = repo(Fake(fail = true), Fake(macOnline = false))
        r.refresh()
        assertEquals(Origin.SERVER_COPY, r.state.value.view!!.origin)
    }

    @Test fun emitsCacheFirstOffline() = runTest {
        repo(Fake(), Fake()).refresh() // fills the cache
        val r = repo(Fake(fail = true), Fake(fail = true))
        r.loadCache()
        assertEquals(Origin.CACHE, r.state.value.view!!.origin)
        r.refresh()
        val s = r.state.value
        assertEquals(Origin.CACHE, s.view!!.origin)
        assertNull(s.error)
    }

    @Test fun rejectsRollback() = runTest {
        val mac = Fake()
        val r = repo(mac, Fake(fail = true))
        r.refresh()
        assertEquals(2L, r.state.value.view!!.seq)
        mac.seq = 1
        r.refresh()
        assertEquals(2L, r.state.value.view!!.seq)
    }

    @Test fun historyOpensOldSnapshot() = runTest {
        val r = repo(null, Fake())
        val v = r.at(1)
        assertEquals(true, v.historic)
        assertEquals(listOf("uno", "dos ñ"), v.tabs.map { it.text })
    }

    @Test fun macOnlyWorksWithoutServer() = runTest {
        val r = Repository(Fake(), null, Cache(tmp.root), keys)
        r.refresh()
        assertEquals(Origin.MAC_TAILSCALE, r.state.value.view!!.origin)
        assertEquals(2, r.history(null).size) // history comes from the Mac's own copy
    }

    @Test fun serverOnlyNeverAsksTheMac() = runTest {
        val r = Repository(null, Fake(macOnline = false), Cache(tmp.root), keys)
        r.refresh()
        assertEquals(Origin.SERVER_COPY, r.state.value.view!!.origin)
    }

    @Test fun rejectedKeyReportsAuthError() = runTest {
        val r = repo(Fake(rejected = true), Fake(rejected = true))
        r.refresh()
        assertTrue(r.state.value.error is AuthRejectedException)
    }

    @Test fun reconnectsAfterSocketClose() = runTest {
        var attempts = 0
        val events = reconnecting(10, 100) {
            flow {
                attempts++
                if (attempts < 3) throw IOException("closed")
                emit(Event("snapshot", seq = attempts.toLong()))
            }
        }.take(1).toList()
        assertEquals(3L, events.single().seq)
    }
}
