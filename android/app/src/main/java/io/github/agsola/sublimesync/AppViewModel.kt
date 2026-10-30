package io.github.agsola.sublimesync

import android.app.Application
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.agsola.sublimesync.data.Cache
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import io.github.agsola.sublimesync.model.PhoneState
import io.github.agsola.sublimesync.data.PhoneStateStore
import io.github.agsola.sublimesync.data.DraftSyncer
import io.github.agsola.sublimesync.data.DraftStore
import io.github.agsola.sublimesync.data.Draft
import io.github.agsola.sublimesync.data.CommandApi
import io.github.agsola.sublimesync.data.HttpSource
import io.github.agsola.sublimesync.data.Repository
import io.github.agsola.sublimesync.data.TabsView
import io.github.agsola.sublimesync.model.ListItem
import io.github.agsola.sublimesync.model.PairPayload
import io.github.agsola.sublimesync.security.KeyVault
import io.github.agsola.sublimesync.settings.Layout
import io.github.agsola.sublimesync.settings.SettingsStore
import io.github.agsola.sublimesync.settings.Sources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.InvalidKeyException

sealed interface Screen {
    data object Locked : Screen
    data object Pair : Screen
    /** Windows as tabs; inside, a list or Sublime's tab bar (see Settings.layout). */
    data object Main : Screen
    /** One tab's full text (from the list layout). */
    data class Tab(val tabId: Long) : Screen
    /** All tabs as a searchable list. */
    data object Overview : Screen
    data object History : Screen
    data object Settings : Screen
    /** Editing a phone draft. */
    data class Editor(val draftId: String) : Screen
    data object Drafts : Screen
    data object Hidden : Screen
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val vault = KeyVault(app)
    val settings = SettingsStore(app)

    /** The tab shown in the reader, by Sublime view id. */
    private val _selectedTab = MutableStateFlow<Long?>(null)
    val selectedTab: StateFlow<Long?> = _selectedTab.asStateFlow()

    fun select(tabId: Long) {
        _selectedTab.value = tabId
    }

    /** The window shown on the main screen, by Sublime window id. */
    private val _selectedWindow = MutableStateFlow<Long?>(null)
    val selectedWindow: StateFlow<Long?> = _selectedWindow.asStateFlow()

    fun selectWindow(windowId: Long) {
        _selectedWindow.value = windowId
    }

    /** Opens a tab: in its own screen for the list layout, in place for the tab-bar layout. */
    fun openTab(tabId: Long, windowId: Long) {
        _selectedTab.value = tabId
        _selectedWindow.value = windowId
        _screen.value = if (settings.settings.value.layout == Layout.WINDOWS_LIST) Screen.Tab(tabId) else Screen.Main
    }

    private var keyring: Map<String, io.github.agsola.sublimesync.crypto.Keys>? = null
    private var activeSources: Sources? = null

    init {
        viewModelScope.launch {
            // Rebuild the connections when the user picks other sources.
            settings.settings.collect { s ->
                if (repository != null && s.sources != activeSources) runCatching { startSession() }
            }
        }
    }
    private val cacheDir = File(app.filesDir, "cache")

    private val _screen = MutableStateFlow<Screen>(Screen.Locked)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** Shown on the pair screen, e.g. after the key store was invalidated. */
    private val _pairMessage = MutableStateFlow<Int?>(null)
    val pairMessage: StateFlow<Int?> = _pairMessage.asStateFlow()

    var repository: Repository? = null
        private set

    /** Everything that needs the decrypted keys runs here; lock() cancels it. */
    private var session: CoroutineScope? = null

    private val _historic = MutableStateFlow<TabsView?>(null)
    val historic: StateFlow<TabsView?> = _historic.asStateFlow()

    private val _history = MutableStateFlow<List<ListItem>>(emptyList())
    val history: StateFlow<List<ListItem>> = _history.asStateFlow()
    private var historyJob: Job? = null

    private var pendingPair: PairPayload? = null

    /** Survives Activity recreation, so the re-lock timer can't be reset by it. */
    private var backgroundedAt = 0L

    val isPaired: Boolean get() = vault.pairing() != null

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
        // Keep what was typed and try to send it before Android may stop the app.
        drafts?.flush()
        syncNow()
    }

    fun onForeground() {
        if (backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS) lock()
        backgroundedAt = 0L
    }

    /** Called after a successful fingerprint/PIN prompt. */
    fun unlocked() {
        pendingPair?.let { p ->
            pendingPair = null
            pair(p)
            return
        }
        if (vault.pairing() == null) {
            _screen.value = Screen.Pair
            return
        }
        try {
            startSession()
            _screen.value = Screen.Main
        } catch (_: UserNotAuthenticatedException) {
            _screen.value = Screen.Locked
        } catch (e: Exception) {
            if (e is KeyPermanentlyInvalidatedException || e is InvalidKeyException) keyStoreInvalidated() else throw e
        }
    }

    fun lock() {
        drafts?.flush()
        drafts = null
        syncer = null
        phoneStateStore = null
        _phoneState.value = PhoneState()
        _draftList.value = emptyList()
        session?.cancel()
        session = null
        repository = null
        keyring = null
        _historic.value = null
        _history.value = emptyList()
        _screen.value = Screen.Locked
    }

    fun pair(p: PairPayload) {
        keyring = null
        try {
            vault.store(p)
        } catch (_: UserNotAuthenticatedException) {
            // The 5-minute window after unlocking has passed: ask again, then store.
            pendingPair = p
            lock()
            return
        } catch (e: Exception) {
            if (e is KeyPermanentlyInvalidatedException || e is InvalidKeyException) {
                vault.reset()
                pendingPair = p
                lock()
                return
            }
            throw e
        }
        _pairMessage.value = null
        startSession()
        _screen.value = Screen.Main
    }

    fun unpair() {
        lock()
        vault.reset()
        cacheDir.deleteRecursively()
        _screen.value = Screen.Pair
    }

    private fun keyStoreInvalidated() {
        // Happens when the screen lock is removed: the wrapped keys are gone for good.
        vault.reset()
        cacheDir.deleteRecursively()
        _pairMessage.value = R.string.key_invalidated
        _screen.value = Screen.Pair
    }

    private fun startSession() {
        val pairing = vault.pairing() ?: return
        val keyring = this.keyring ?: vault.unlock().also { this.keyring = it }
        val current = keyring[pairing.currentKeyId] ?: return
        val sources = settings.settings.value.sources
        activeSources = sources
        val mac = if (sources == Sources.SERVER_ONLY) null else runCatching { HttpSource(pairing.mac, current.bearerMac(), 1_500) }.getOrNull()
        val server = if (sources == Sources.MAC_ONLY) null else HttpSource(pairing.server, current.bearerServer(), 10_000)
        val repo = Repository(mac, server, Cache(cacheDir), keyring)
        session?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob())
        session = scope
        repository = repo
        repo.startLive(scope)
        scope.launch {
            repo.loadCache()
            repo.refresh()
        }

        // Editing: drafts and hidden/private flags, saved on the phone and synced.
        val targets = listOfNotNull<CommandApi>(mac, server)
        val ps = PhoneStateStore(File(app.filesDir, "phone-state"), keyring, current)
        val ds = DraftStore(File(app.filesDir, "drafts"), current, keyring).also { it.discardUntouched() }
        val sy = DraftSyncer(ds, { targets }, current, keyring)
        phoneStateStore = ps
        drafts = ds
        syncer = sy
        scope.launch { ps.state.collect { _phoneState.value = it } }
        scope.launch { ds.drafts.collect { _draftList.value = it } }
        scope.launch {
            while (true) {
                runCatching { sy.syncOnce() }
                (server ?: mac)?.let { api -> runCatching { ps.sync(api) } }
                withTimeoutOrNull(settings.settings.value.syncSeconds * 1000L) { syncKick.receive() }
            }
        }
    }

    // --- editing, hiding, private, closing ---------------------------------

    private var drafts: DraftStore? = null
    private var syncer: DraftSyncer? = null
    private var phoneStateStore: PhoneStateStore? = null
    private val syncKick = Channel<Unit>(Channel.CONFLATED)

    private val _phoneState = MutableStateFlow(PhoneState())
    val phoneState: StateFlow<PhoneState> = _phoneState.asStateFlow()

    private val _draftList = MutableStateFlow<List<Draft>>(emptyList())
    val draftList: StateFlow<List<Draft>> = _draftList.asStateFlow()

    /** Sends pending edits and flags now instead of waiting for the interval. */
    fun syncNow() {
        syncKick.trySend(Unit)
    }

    /** Opens the editor on a tab, reusing its draft if there is one. */
    fun edit(tab: io.github.agsola.sublimesync.model.Tab) {
        if (tab.truncated || tab.text.length > MAX_EDIT_CHARS) {
            // Cut tabs would be edited from incomplete text; huge ones make the editor crawl.
            val msg = if (tab.truncated) R.string.truncated_cannot_edit else R.string.too_large_to_edit
            android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val ds = drafts ?: return
        val d = ds.forTab(tab.uid, tab.draft) ?: ds.create(sourceUid = tab.uid.ifBlank { null }, title = tab.title, text = tab.text)
        _screen.value = Screen.Editor(d.id)
    }

    fun newTab() {
        val ds = drafts ?: return
        _screen.value = Screen.Editor(ds.create(sourceUid = null, title = "", text = "").id)
    }

    fun draft(id: String): Draft? = drafts?.get(id)

    fun updateDraft(id: String, text: String) {
        drafts?.update(id, text)
    }

    /** Drafts opened but never edited are discarded on leaving the editor. */
    fun leaveEditor(draftId: String) {
        drafts?.let { ds ->
            ds.flush()
            ds.get(draftId)?.let { if (it.rev == 1L && it.commandId == null) ds.delete(draftId) }
        }
        syncNow()
        _screen.value = Screen.Main
    }

    fun flushDrafts() {
        drafts?.flush()
    }

    fun deleteDraft(id: String) {
        drafts?.delete(id)
    }

    fun setHidden(uid: String, hidden: Boolean) {
        phoneStateStore?.setHidden(uid, hidden)
        syncNow()
    }

    fun setPrivate(uid: String, private: Boolean) {
        phoneStateStore?.setPrivate(uid, private)
        syncNow()
    }

    /** Asks Sublime to close a tab; [done] gets the answer (null if nothing could be reached). */
    fun closeInSublime(tab: io.github.agsola.sublimesync.model.Tab, done: (io.github.agsola.sublimesync.model.Result?) -> Unit) {
        val sy = syncer ?: return done(null)
        session?.launch {
            val r = sy.close(tab.uid)
            // A closed "✎" tab whose draft has nothing unsent: the draft is done.
            if (r?.status == "applied" && tab.draft.isNotBlank()) {
                drafts?.get(tab.draft)?.let { if (it.rev == it.sentRev) drafts?.delete(it.id) }
            }
            done(r)
            repository?.refresh()
        }
    }

    /** True only while a refresh the user asked for (pull down) is running. */
    private val _userRefreshing = MutableStateFlow(false)
    val userRefreshing: StateFlow<Boolean> = _userRefreshing.asStateFlow()

    fun refresh() {
        val repo = repository ?: return
        _userRefreshing.value = true
        session?.launch {
            try { repo.refresh() } finally { _userRefreshing.value = false }
        }
    }

    fun open(screen: Screen) {
        _screen.value = screen
    }

    fun loadHistory(more: Boolean = false) {
        val repo = repository ?: return
        if (historyJob?.isActive == true) return
        historyJob = session?.launch {
            val before = if (more) _history.value.lastOrNull()?.seq else null
            val page = runCatching { repo.history(before) }.getOrDefault(emptyList())
            val merged = (if (more) _history.value + page else page).distinctBy { it.seq }
            _history.value = merged
        }
    }

    fun openHistoric(seq: Long) {
        val repo = repository ?: return
        session?.launch {
            runCatching { repo.at(seq) }.onSuccess {
                if (repository !== repo) return@onSuccess
                _historic.value = it
                _screen.value = Screen.Main
            }
        }
    }

    fun backToNow() {
        _historic.value = null
        _screen.value = Screen.Main
    }

    fun back(): Boolean = when (_screen.value) {
        Screen.Overview, Screen.History, Screen.Settings, is Screen.Tab, Screen.Drafts -> { _screen.value = Screen.Main; true }
        is Screen.Editor -> { leaveEditor((_screen.value as Screen.Editor).draftId); true }
        Screen.Hidden -> { _screen.value = Screen.Settings; true }
        Screen.Pair -> if (repository != null) { _screen.value = Screen.Main; true } else false
        Screen.Main -> if (_historic.value != null) { backToNow(); true } else false
        else -> false
    }

    companion object {
        /** Above this the phone editor gets too slow to be useful. */
        const val MAX_EDIT_CHARS = 5_000_000
        private const val RELOCK_AFTER_MS = 5 * 60 * 1000L
    }
}
