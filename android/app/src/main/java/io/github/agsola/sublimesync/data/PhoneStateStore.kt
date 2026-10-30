package io.github.agsola.sublimesync.data

import io.github.agsola.sublimesync.crypto.Envelope
import io.github.agsola.sublimesync.crypto.Keys
import io.github.agsola.sublimesync.model.PhoneEntry
import io.github.agsola.sublimesync.model.PhoneState
import io.github.agsola.sublimesync.model.Tab
import io.github.agsola.sublimesync.model.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

const val PHONE_STATE = "phone-state"

/**
 * Hidden and private flags per tab, saved sealed on the phone right away and
 * shared through the relay (or the Mac) as a versioned document.
 */
class PhoneStateStore(
    private val file: File,
    private val keyring: Map<String, Keys>,
    private val current: Keys,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Local(val state: PhoneState = PhoneState(), val dirty: Boolean = false)

    private var local = load()
    private val _state = MutableStateFlow(local.state)
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    fun setHidden(uid: String, hidden: Boolean) = edit(uid) { it.copy(hidden = hidden) }
    fun setPrivate(uid: String, private: Boolean) = edit(uid) { it.copy(private = private) }

    private fun edit(uid: String, change: (PhoneEntry) -> PhoneEntry) {
        if (uid.isBlank()) return
        val entry = change(local.state.entries[uid] ?: PhoneEntry()).copy(at = clock())
        save(Local(PhoneState(local.state.entries + (uid to entry)), dirty = true))
    }

    /** Merges with the shared copy and uploads ours, retrying on version conflicts. */
    suspend fun sync(api: CommandApi) {
        var remote = api.getState(PHONE_STATE)
        repeat(3) {
            val remoteState = remote?.envelope?.let { decode(it, Envelope.stateAad(PHONE_STATE)) } ?: PhoneState()
            val merged = merge(remoteState, local.state)
            if (remote != null && merged == remoteState) {
                saveSynced(merged)
                return
            }
            val env = Envelope.seal(current, Envelope.stateAad(PHONE_STATE), json.encodeToString(PhoneState.serializer(), merged).toByteArray())
            when (val r = api.putState(PHONE_STATE, remote?.version ?: 0, env)) {
                is PutResult.Ok -> {
                    saveSynced(merged)
                    return
                }
                is PutResult.Conflict -> remote = r.current
            }
        }
    }

    /**
     * Stores what is now on the server, keeping any change the user made while
     * the request was in flight (still dirty, so the next sync uploads it).
     */
    private fun saveSynced(uploaded: PhoneState) {
        val latest = merge(uploaded, local.state)
        save(Local(latest, dirty = latest != uploaded))
    }

    private fun decode(env: ByteArray, aad: String): PhoneState? =
        runCatching { json.decodeFromString<PhoneState>(String(Envelope.open(keyring, aad, env))) }.getOrNull()

    private fun load(): Local = runCatching {
        json.decodeFromString<Local>(String(Envelope.open(keyring, LOCAL_AAD, file.readBytes())))
    }.getOrDefault(Local())

    private fun save(l: Local) {
        local = l
        _state.value = l.state
        writeAtomically(file, Envelope.seal(current, LOCAL_AAD, json.encodeToString(Local.serializer(), l).toByteArray()))
    }

    companion object {
        private const val LOCAL_AAD = "state:local"

        /** Per tab, the most recent change wins. */
        fun merge(a: PhoneState, b: PhoneState): PhoneState {
            val out = a.entries.toMutableMap()
            for ((uid, e) in b.entries) {
                val cur = out[uid]
                if (cur == null || e.at > cur.at) out[uid] = e
            }
            return PhoneState(out)
        }
    }
}

/** Tabs not hidden on this phone. Tabs without a uid (old agents) are always shown. */
fun visibleTabs(tabs: List<Tab>, state: PhoneState): List<Tab> =
    tabs.filter { it.uid.isBlank() || state.entries[it.uid]?.hidden != true }

fun isPrivate(tab: Tab, state: PhoneState) = tab.uid.isNotBlank() && state.entries[tab.uid]?.private == true

const val MASK = "••••••"

fun maskedTitle(tab: Tab, state: PhoneState) = if (isPrivate(tab, state)) MASK else io.github.agsola.sublimesync.ui.displayTitle(tab)

/** A draft of a private tab is private too. */
fun isPrivateDraft(d: Draft, state: PhoneState): Boolean =
    listOfNotNull(d.sourceUid, d.tabUid).any { state.entries[it]?.private == true }
