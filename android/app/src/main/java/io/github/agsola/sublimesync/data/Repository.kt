package io.github.agsola.sublimesync.data

import io.github.agsola.sublimesync.crypto.Envelope
import io.github.agsola.sublimesync.crypto.Keys
import io.github.agsola.sublimesync.model.ListItem
import io.github.agsola.sublimesync.model.Manifest
import io.github.agsola.sublimesync.model.SnapshotJson
import io.github.agsola.sublimesync.model.Tab
import io.github.agsola.sublimesync.model.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64

enum class Origin { MAC_TAILSCALE, SERVER_LIVE, SERVER_COPY, CACHE }

data class TabsView(
    val seq: Long,
    /** When the Mac took this snapshot (unix ms). */
    val at: Long,
    val tabs: List<Tab>,
    val origin: Origin,
    val historic: Boolean = false,
)

data class RepoState(
    val view: TabsView? = null,
    val refreshing: Boolean = false,
    val error: Throwable? = null,
)

/**
 * Reads from the Mac (over Tailscale) and the relay in parallel, preferring
 * the Mac when it answers within [macPreferMs]; falls back to the cache.
 */
class Repository(
    private val mac: Source?,
    private val server: Source?,
    private val cache: Cache,
    private val keyring: Map<String, Keys>,
    private val macPreferMs: Long = 1_500,
) {
    private val _state = MutableStateFlow(RepoState())
    val state: StateFlow<RepoState> = _state.asStateFlow()
    private val refreshLock = Mutex()

    /** Shows the cached snapshot (if any) before the network answers. */
    suspend fun loadCache() {
        if (_state.value.view != null) return
        val snap = cache.latest() ?: return
        runCatching { materialize(snap, Origin.CACHE, null) }.onSuccess { v ->
            _state.update { if (it.view == null) it.copy(view = v) else it }
        }
    }

    suspend fun refresh() = refreshLock.withLock {
        _state.update { it.copy(refreshing = true) }
        try {
            val failures = mutableListOf<Throwable>()
            val (snap, origin, src) = fetchLatest(failures) ?: run {
                loadCache()
                val shown = _state.value.view
                val rejected = failures.any { it is HttpStatusException && it.code == 401 }
                _state.update {
                    it.copy(
                        view = shown?.copy(origin = Origin.CACHE),
                        error = when {
                            rejected -> AuthRejectedException()
                            shown == null -> NoSourceException()
                            else -> null
                        },
                    )
                }
                return@withLock
            }
            if (snap.seq < cache.maxSeq) {
                // Older than what we've already shown (rolled back or a lagging
                // source): keep the current view and its label untouched.
                return@withLock
            }
            val current = _state.value.view
            val view = if (current != null && current.seq == snap.seq && !current.historic) {
                current.copy(origin = origin)
            } else {
                materialize(snap, origin, src)
            }
            cache.maxSeq = maxOf(cache.maxSeq, snap.seq)
            _state.update { it.copy(view = view, error = null) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(error = e) }
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetchLatest(failures: MutableList<Throwable>): Triple<SnapshotJson, Origin, Source>? = coroutineScope {
        val fromMac = mac?.let { m ->
            async { withTimeoutOrNull(macPreferMs) { runCatching { m.latest() }.onFailure { failures += it }.getOrNull() } }
        }
        val fromServer = server?.let { srv ->
            async {
                runCatching {
                    val snap = srv.latest()
                    val online = runCatching { srv.status().macOnline }.getOrDefault(false)
                    snap to online
                }.onFailure { failures += it }.getOrNull()
            }
        }
        val m = fromMac?.await()
        if (m != null) {
            fromServer?.cancel()
            return@coroutineScope Triple(m, Origin.MAC_TAILSCALE, mac!!)
        }
        val s = fromServer?.await() ?: return@coroutineScope null
        Triple(s.first, if (s.second) Origin.SERVER_LIVE else Origin.SERVER_COPY, server!!)
    }

    /** History lives on the relay; the Mac keeps its own copy, used when the relay is off. */
    private val historySource: Source get() = server ?: mac ?: throw NoSourceException()

    /** Opens a past snapshot. */
    suspend fun at(seq: Long): TabsView =
        materialize(historySource.snapshot(seq), if (server != null) Origin.SERVER_COPY else Origin.MAC_TAILSCALE, historySource, historic = true)

    suspend fun history(before: Long?, limit: Int = 200): List<ListItem> = historySource.list(before, limit)

    /**
     * Subscribes to live events from both sources until [scope] ends, and also
     * refreshes every [pollMs] so a silently dead socket can't leave a stale
     * "live" label.
     */
    fun startLive(scope: CoroutineScope, pollMs: Long = 60_000): Job = scope.launch {
        val sources = listOfNotNull(mac, server)
        for (src in sources) launch { src.events().collect { refresh() } }
        launch {
            while (true) {
                delay(pollMs)
                refresh()
            }
        }
    }

    private suspend fun materialize(snap: SnapshotJson, origin: Origin, src: Source?, historic: Boolean = false): TabsView {
        val env = Base64.getDecoder().decode(snap.envelope)
        val manifest = json.decodeFromString<Manifest>(String(Envelope.open(keyring, Envelope.manifestAad(snap.seq), env)))
        require(manifest.seq == snap.seq) { "manifest seq mismatch" }
        val gate = Semaphore(6)
        val tabs = coroutineScope {
            manifest.tabs.map { ref ->
                async { gate.withPermit { loadTab(ref.blob, src) } }
            }.awaitAll()
        }.sortedWith(compareBy({ it.window }, { it.index }))
        if (!historic) cache.putLatest(snap, manifest.tabs.map { it.blob }.toSet())
        return TabsView(snap.seq, manifest.created, tabs, origin, historic)
    }

    private suspend fun loadTab(id: String, preferred: Source?): Tab {
        val env = cache.blob(id) ?: run {
            val order = listOfNotNull(preferred, mac, server).distinct()
            var last: Exception? = null
            var got: ByteArray? = null
            for (s in order) {
                got = try { s.blob(id) } catch (e: Exception) { last = e; null }
                if (got != null) break
            }
            (got ?: throw (last ?: NoSourceException())).also { cache.putBlob(id, it) }
        }
        return json.decodeFromString(String(Envelope.open(keyring, Envelope.blobAad(id), env)))
    }
}

class NoSourceException : Exception("neither the Mac nor the server could be reached")
class AuthRejectedException : Exception("the key was rejected; pair again")
