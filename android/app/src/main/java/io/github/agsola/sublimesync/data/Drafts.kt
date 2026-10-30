package io.github.agsola.sublimesync.data

import io.github.agsola.sublimesync.crypto.Envelope
import io.github.agsola.sublimesync.crypto.Keys
import io.github.agsola.sublimesync.model.Command
import io.github.agsola.sublimesync.model.Result
import io.github.agsola.sublimesync.model.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Base64
import java.util.UUID

enum class DraftStatus { SAVED_LOCAL, SENT, APPLIED, CONFLICT, PENDING_SUBLIME, OFFLINE, ERROR }

@Serializable
data class Draft(
    val id: String,
    val sourceUid: String? = null,
    val title: String,
    val text: String,
    val rev: Long,
    val updatedAt: Long,
    val sentRev: Long = 0,
    val commandId: String? = null,
    val sentAt: Long = 0,
    val status: DraftStatus = DraftStatus.SAVED_LOCAL,
    /** The Sublime tab now showing this draft. */
    val tabUid: String? = null,
    val reason: String = "",
)

/**
 * Drafts saved on the phone, one sealed file each, so nothing typed is lost
 * if the app is closed. update() is in memory; flush() writes (call it often).
 */
class DraftStore(
    private val dir: File,
    private val current: Keys,
    private val keyring: Map<String, Keys>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _drafts = MutableStateFlow(load())
    val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()
    private val dirty = mutableSetOf<String>()

    fun get(id: String) = _drafts.value.firstOrNull { it.id == id }

    /** The draft that edits the given Sublime tab (by its uid or its own "✎" draft id). */
    fun forTab(uid: String, draftId: String): Draft? =
        _drafts.value.firstOrNull { (draftId.isNotBlank() && it.id == draftId) || (uid.isNotBlank() && (it.sourceUid == uid || it.tabUid == uid)) }

    fun create(sourceUid: String?, title: String, text: String, id: String = UUID.randomUUID().toString()): Draft {
        // sentRev = rev: nothing is sent until the text actually changes.
        val d = Draft(id = id, sourceUid = sourceUid, title = title, text = text, rev = 1, updatedAt = clock(), sentRev = 1)
        put(d)
        write(d)
        return d
    }

    @Synchronized
    fun update(id: String, text: String) {
        val d = get(id) ?: return
        if (d.text == text) return
        put(d.copy(text = text, rev = d.rev + 1, updatedAt = clock(), status = DraftStatus.SAVED_LOCAL))
        dirty += id
    }

    @Synchronized
    fun flush() {
        for (id in dirty.toList()) get(id)?.let { write(it) }
        dirty.clear()
    }

    /** Changes bookkeeping fields and saves at once. */
    @Synchronized
    fun mutate(id: String, change: (Draft) -> Draft) {
        val d = get(id) ?: return
        val n = change(d)
        put(n)
        write(n)
        dirty -= id
    }

    /** Drops drafts opened but never edited nor sent (e.g. left when the app was killed). */
    @Synchronized
    fun discardUntouched() {
        _drafts.value.filter { it.rev == 1L && it.commandId == null }.forEach { delete(it.id) }
    }

    @Synchronized
    fun delete(id: String) {
        _drafts.value = _drafts.value.filter { it.id != id }
        File(dir, "$id.draft").delete()
        dirty -= id
    }

    private fun put(d: Draft) {
        _drafts.value = (_drafts.value.filter { it.id != d.id } + d).sortedByDescending { it.updatedAt }
    }

    private fun write(d: Draft) {
        dir.mkdirs()
        writeAtomically(File(dir, "${d.id}.draft"), Envelope.seal(current, Envelope.draftAad(d.id), json.encodeToString(Draft.serializer(), d).toByteArray()))
    }

    private fun load(): List<Draft> = (dir.listFiles { f -> f.name.endsWith(".draft") } ?: emptyArray()).mapNotNull { f ->
        val id = f.name.removeSuffix(".draft")
        runCatching { json.decodeFromString<Draft>(String(Envelope.open(keyring, Envelope.draftAad(id), f.readBytes()))) }.getOrNull()
    }.sortedByDescending { it.updatedAt }
}

/**
 * Sends drafts (only their latest text) and close requests to the Mac or the
 * relay, whichever answers first in [targets] order, and tracks the results.
 */
class DraftSyncer(
    private val store: DraftStore,
    private val targets: () -> List<CommandApi>,
    private val current: Keys,
    private val keyring: Map<String, Keys>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun syncOnce() {
        store.flush()
        for (d in store.drafts.value) {
            if (d.rev > d.sentRev) send(d)
        }
        for (d in store.drafts.value) {
            if (d.commandId != null && (d.status == DraftStatus.SENT || d.status == DraftStatus.PENDING_SUBLIME)) poll(d)
        }
    }

    private suspend fun send(d: Draft) {
        val cmd = Command(
            id = UUID.randomUUID().toString(), type = "draft", created = clock(),
            draftId = d.id, sourceUid = d.sourceUid, title = d.title.ifBlank { firstLine(d.text) }, text = d.text, rev = d.rev,
        )
        if (post(cmd)) {
            store.mutate(d.id) { it.copy(sentRev = d.rev, commandId = cmd.id, sentAt = clock(), status = DraftStatus.SENT, reason = "") }
        } else {
            store.mutate(d.id) { it.copy(status = DraftStatus.OFFLINE) }
        }
    }

    private suspend fun poll(d: Draft) {
        val r = result(d.commandId!!)
        if (r == null) {
            if (clock() - d.sentAt > PENDING_AFTER_MS) store.mutate(d.id) { it.copy(status = DraftStatus.PENDING_SUBLIME) }
            return
        }
        val status = when (r.status) {
            "applied" -> DraftStatus.APPLIED
            "conflict" -> DraftStatus.CONFLICT
            else -> DraftStatus.ERROR
        }
        store.mutate(d.id) { it.copy(status = status, tabUid = r.uid.ifBlank { it.tabUid }, reason = r.reason) }
    }

    /** Asks the Mac to close a tab; waits up to [timeoutMs] for its answer. */
    suspend fun close(uid: String, timeoutMs: Long = 60_000): Result? {
        val cmd = Command(id = UUID.randomUUID().toString(), type = "close", created = clock(), uid = uid)
        if (!post(cmd)) return null
        val start = clock()
        while (clock() - start < timeoutMs) {
            result(cmd.id)?.let { return it }
            delay(1_000)
        }
        return Result(cmd.id, "pending")
    }

    private suspend fun post(cmd: Command): Boolean {
        val env = Envelope.seal(current, Envelope.commandAad(cmd.id), json.encodeToString(Command.serializer(), cmd).toByteArray())
        for (t in targets()) {
            try {
                t.postCommand(cmd.id, env)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return false
    }

    private suspend fun result(id: String): Result? {
        for (t in targets()) {
            val c = try { t.command(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val env = c?.result?.let { Base64.getDecoder().decode(it) } ?: continue
            return runCatching { json.decodeFromString<Result>(String(Envelope.open(keyring, Envelope.resultAad(id), env))) }.getOrNull()
        }
        return null
    }

    companion object {
        const val PENDING_AFTER_MS = 30_000L
    }
}

/** Title for a new tab: its first non-blank line, shortened. */
fun firstLine(text: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(40) ?: ""
