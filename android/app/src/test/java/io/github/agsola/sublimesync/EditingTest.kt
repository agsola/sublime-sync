package io.github.agsola.sublimesync

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.agsola.sublimesync.crypto.Envelope
import io.github.agsola.sublimesync.crypto.Keys
import io.github.agsola.sublimesync.data.CommandApi
import io.github.agsola.sublimesync.data.DraftStatus
import io.github.agsola.sublimesync.data.DraftStore
import io.github.agsola.sublimesync.data.DraftSyncer
import io.github.agsola.sublimesync.data.PhoneStateStore
import io.github.agsola.sublimesync.data.PutResult
import io.github.agsola.sublimesync.data.maskedTitle
import io.github.agsola.sublimesync.data.visibleTabs
import io.github.agsola.sublimesync.data.writeAtomically
import io.github.agsola.sublimesync.ui.editor.FlushThrottle
import io.github.agsola.sublimesync.model.Command
import io.github.agsola.sublimesync.model.CommandJson
import io.github.agsola.sublimesync.model.PhoneEntry
import io.github.agsola.sublimesync.model.PhoneState
import io.github.agsola.sublimesync.model.Result
import io.github.agsola.sublimesync.data.StateJson
import io.github.agsola.sublimesync.model.Tab
import io.github.agsola.sublimesync.model.json
import io.github.agsola.sublimesync.ui.editor.AutoIndent
import io.github.agsola.sublimesync.ui.editor.EditHistory
import io.github.agsola.sublimesync.ui.editor.replaceAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Base64

class EditingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val keys = Keys.derive(ByteArray(32) { it.toByte() })
    private val ring = mapOf(keys.id to keys)
    private var now = 1_000_000L

    // --- envelope v2 -------------------------------------------------------

    @Test fun sealV2MatchesGoVector() {
        val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject
        val expected = Base64.getDecoder().decode(v["envelopeV2"]!!.jsonPrimitive.content)
        val sealed = Envelope.sealWithNonce(keys, "blob:abc", v["plaintext"]!!.jsonPrimitive.content.toByteArray(), ByteArray(12) { it.toByte() })
        assertArrayEquals(expected, sealed)
    }

    @Test fun sealOpenRoundTrip() {
        val env = Envelope.seal(keys, "draft:x", "hola ✎".toByteArray())
        assertEquals("hola ✎", String(Envelope.open(ring, "draft:x", env)))
    }

    // --- phone state -------------------------------------------------------

    @Test fun mergeNewestWins() {
        val a = PhoneState(mapOf("u" to PhoneEntry(hidden = true, at = 5)))
        val b = PhoneState(mapOf("u" to PhoneEntry(hidden = false, private = true, at = 9), "v" to PhoneEntry(hidden = true, at = 1)))
        val m = PhoneStateStore.merge(a, b)
        assertEquals(PhoneEntry(hidden = false, private = true, at = 9), m.entries["u"])
        assertTrue(m.entries["v"]!!.hidden)
    }

    @Test fun unhideSurvivesMerge() {
        val store = PhoneStateStore(tmp.newFile("s"), ring, keys) { now }
        store.setHidden("u", true)
        val remote = store.state.value
        now += 10
        store.setHidden("u", false)
        assertFalse(PhoneStateStore.merge(remote, store.state.value).entries["u"]!!.hidden)
    }

    @Test fun persistsAcrossReopen() {
        val f = tmp.newFile("s2")
        PhoneStateStore(f, ring, keys) { now }.setPrivate("u", true)
        assertTrue(PhoneStateStore(f, ring, keys) { now }.state.value.entries["u"]!!.private)
    }

    @Test fun syncRetriesOnConflict() = runTest {
        val api = FakeApi()
        // Another phone already stored a state.
        api.state = StateJson(1, Envelope.seal(keys, Envelope.stateAad("phone-state"),
            json.encodeToString(PhoneState.serializer(), PhoneState(mapOf("other" to PhoneEntry(hidden = true, at = 1)))).toByteArray()))
        api.conflictsLeft = 1
        val store = PhoneStateStore(tmp.newFile("s3"), ring, keys) { now }
        store.setHidden("mine", true)
        store.sync(api)
        val saved = json.decodeFromString<PhoneState>(String(Envelope.open(ring, Envelope.stateAad("phone-state"), api.state!!.envelope!!)))
        assertTrue(saved.entries["other"]!!.hidden)
        assertTrue(saved.entries["mine"]!!.hidden)
        assertTrue(store.state.value.entries["other"]!!.hidden)
    }

    @Test fun toggleDuringSyncIsKept() = runTest {
        val api = FakeApi()
        val store = PhoneStateStore(tmp.newFile("s4"), ring, keys) { now }
        api.onPutState = { now += 1; store.setPrivate("t", true) } // user taps while the upload is in flight
        store.sync(api)
        assertTrue(store.state.value.entries["t"]!!.private)
        store.sync(api) // and it is uploaded on the next sync
        val saved = json.decodeFromString<PhoneState>(String(Envelope.open(ring, Envelope.stateAad("phone-state"), api.state!!.envelope!!)))
        assertTrue(saved.entries["t"]!!.private)
    }

    // --- drafts ------------------------------------------------------------

    @Test fun atomicWriteReplacesFile() {
        val f = tmp.newFile("a")
        writeAtomically(f, "one".toByteArray())
        writeAtomically(f, "two".toByteArray())
        assertEquals("two", f.readText())
        assertTrue(f.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun flushThrottleFlushesDuringContinuousTyping() {
        val t = FlushThrottle(500)
        val flushedAt = (0L..2_000L step 100).filter { t.due(it) }
        assertEquals(listOf(0L, 500L, 1_000L, 1_500L, 2_000L), flushedAt)
    }

    @Test fun privateDraftMasked() {
        val st = PhoneState(mapOf("u" to PhoneEntry(private = true, at = 1)))
        val d = io.github.agsola.sublimesync.data.Draft(id = "d", sourceUid = "u", title = "secret", text = "x", rev = 1, updatedAt = 0)
        assertTrue(io.github.agsola.sublimesync.data.isPrivateDraft(d, st))
        assertFalse(io.github.agsola.sublimesync.data.isPrivateDraft(d.copy(sourceUid = "other"), st))
    }


    @Test fun draftSurvivesStoreReopen() {
        val dir = tmp.newFolder("d")
        val s = DraftStore(dir, keys, ring) { now }
        val d = s.create(sourceUid = "u1", title = "notas", text = "a")
        s.update(d.id, "ab")
        s.flush()
        val reopened = DraftStore(dir, keys, ring) { now }.drafts.value.single()
        assertEquals("ab", reopened.text)
        assertEquals(2L, reopened.rev)
        assertEquals("u1", reopened.sourceUid)
    }

    @Test fun untouchedDraftNotSent() = runTest {
        val api = FakeApi()
        val s = DraftStore(tmp.newFolder("d6"), keys, ring) { now }
        s.create("u1", "n", "unchanged text")
        DraftSyncer(s, { listOf(api) }, keys, ring) { now }.syncOnce()
        assertTrue(api.posted.isEmpty())
    }

    @Test fun sendsOnlyLatestRevision() = runTest {
        val api = FakeApi()
        val s = DraftStore(tmp.newFolder("d2"), keys, ring) { now }
        val d = s.create(null, "n", "a")
        s.update(d.id, "ab")
        s.update(d.id, "abc")
        val syncer = DraftSyncer(s, { listOf(api) }, keys, ring) { now }
        syncer.syncOnce()
        syncer.syncOnce() // nothing new to send
        assertEquals(1, api.posted.size)
        val cmd = json.decodeFromString<Command>(String(Envelope.open(ring, Envelope.commandAad(api.posted[0].first), api.posted[0].second)))
        assertEquals("abc", cmd.text)
        assertEquals(3L, cmd.rev)
        assertEquals("draft", cmd.type)
    }

    @Test fun retriesWhenOffline() = runTest {
        val api = FakeApi().apply { offline = true }
        val s = DraftStore(tmp.newFolder("d3"), keys, ring) { now }
        s.create(null, "n", "a").also { s.update(it.id, "a!") }
        val syncer = DraftSyncer(s, { listOf(api) }, keys, ring) { now }
        syncer.syncOnce()
        assertEquals(DraftStatus.OFFLINE, s.drafts.value.single().status)
        api.offline = false
        syncer.syncOnce()
        assertEquals(1, api.posted.size)
        assertEquals(DraftStatus.SENT, s.drafts.value.single().status)
    }

    @Test fun fallsBackToServerWhenMacDown() = runTest {
        val mac = FakeApi().apply { offline = true }
        val server = FakeApi()
        val s = DraftStore(tmp.newFolder("d4"), keys, ring) { now }
        s.create(null, "n", "a").also { s.update(it.id, "a!") }
        DraftSyncer(s, { listOf(mac, server) }, keys, ring) { now }.syncOnce()
        assertEquals(1, server.posted.size)
    }

    @Test fun statusFromResult() = runTest {
        val api = FakeApi()
        val s = DraftStore(tmp.newFolder("d5"), keys, ring) { now }
        s.create(null, "n", "a").also { s.update(it.id, "a!") }
        val syncer = DraftSyncer(s, { listOf(api) }, keys, ring) { now }
        syncer.syncOnce()
        now += 31_000
        syncer.syncOnce()
        assertEquals(DraftStatus.PENDING_SUBLIME, s.drafts.value.single().status)
        val id = api.posted.single().first
        api.answer(keys, Result(id, "conflict", uid = "u2"))
        syncer.syncOnce()
        val d = s.drafts.value.single()
        assertEquals(DraftStatus.CONFLICT, d.status)
        assertEquals("u2", d.tabUid)
    }

    // --- editor ------------------------------------------------------------

    @Test fun undoRedoCoalesces() {
        val h = EditHistory(TextFieldValue("a"))
        h.record(TextFieldValue("ab"), atMs = 0)
        h.record(TextFieldValue("abc"), atMs = 500) // same burst
        h.record(TextFieldValue("abc d"), atMs = 3_000)
        assertEquals("abc", h.undo()!!.text)
        assertEquals("a", h.undo()!!.text)
        assertNull(h.undo())
        assertEquals("abc", h.redo()!!.text)
    }

    @Test fun autoIndentCopiesLeadingWhitespace() {
        val old = TextFieldValue("    foo", TextRange(7))
        val new = TextFieldValue("    foo\n", TextRange(8))
        val r = AutoIndent.apply(old, new)
        assertEquals("    foo\n    ", r.text)
        assertEquals(TextRange(12), r.selection)
        // pasting several lines is left alone
        val paste = TextFieldValue("    foo\na\nb", TextRange(11))
        assertEquals(paste, AutoIndent.apply(old, paste))
    }

    @Test fun replaceAll() {
        assertEquals("X b X" to 2, replaceAll("a b A", "a", "X", ignoreCase = true))
        assertEquals("a.b" to 0, replaceAll("a.b", ".x", "y", ignoreCase = true))
    }

    // --- hidden / private --------------------------------------------------

    @Test fun hiddenFilteredAndPrivateMasked() {
        val tabs = listOf(Tab(1, uid = "a", title = "uno"), Tab(2, uid = "b", title = "dos"), Tab(3, title = "sin uid"))
        val st = PhoneState(mapOf("a" to PhoneEntry(hidden = true, at = 1), "b" to PhoneEntry(private = true, at = 1)))
        assertEquals(listOf(2L, 3L), visibleTabs(tabs, st).map { it.id })
        assertEquals("••••••", maskedTitle(tabs[1], st))
        assertEquals("sin uid", maskedTitle(tabs[2], st))
    }
}

/** In-memory Mac/relay for command and state calls. */
class FakeApi : CommandApi {
    var offline = false
    val posted = mutableListOf<Pair<String, ByteArray>>()
    private val results = mutableMapOf<String, ByteArray>()
    var state: StateJson? = null
    var conflictsLeft = 0
    var onGetState: () -> Unit = {}
    var onPutState: () -> Unit = {}

    fun answer(k: Keys, r: Result) {
        results[r.id] = Envelope.seal(k, Envelope.resultAad(r.id), json.encodeToString(Result.serializer(), r).toByteArray())
    }

    override suspend fun postCommand(id: String, env: ByteArray) {
        if (offline) throw IOException("offline")
        posted += id to env
    }

    override suspend fun command(id: String): CommandJson? {
        if (offline) throw IOException("offline")
        if (posted.none { it.first == id }) return null
        return CommandJson(id, result = results[id]?.let { Base64.getEncoder().encodeToString(it) })
    }

    override suspend fun getState(name: String): StateJson? {
        if (offline) throw IOException("offline")
        onGetState()
        onGetState = {}
        return state
    }

    override suspend fun putState(name: String, ifVersion: Long, env: ByteArray): PutResult {
        if (offline) throw IOException("offline")
        onPutState()
        onPutState = {}
        val cur = state
        if (conflictsLeft > 0 || (cur?.version ?: 0) != ifVersion) {
            conflictsLeft--
            return PutResult.Conflict(cur!!)
        }
        state = StateJson(ifVersion + 1, env)
        return PutResult.Ok(ifVersion + 1)
    }
}
