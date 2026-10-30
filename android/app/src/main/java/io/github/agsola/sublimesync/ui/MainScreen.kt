package io.github.agsola.sublimesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.Screen
import io.github.agsola.sublimesync.crypto.KeyMismatchException
import io.github.agsola.sublimesync.data.AuthRejectedException
import io.github.agsola.sublimesync.data.RepoState
import io.github.agsola.sublimesync.model.PhoneState
import io.github.agsola.sublimesync.data.visibleTabs
import io.github.agsola.sublimesync.data.maskedTitle
import io.github.agsola.sublimesync.data.isPrivate
import io.github.agsola.sublimesync.model.Tab
import io.github.agsola.sublimesync.settings.Layout
import io.github.agsola.sublimesync.settings.Settings
import io.github.agsola.sublimesync.settings.WindowGroup
import io.github.agsola.sublimesync.settings.windows
import io.github.agsola.sublimesync.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** First level: Sublime windows as tabs. Inside, a list of tabs or Sublime's tab bar. */
@Composable
fun MainScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val repoState by (vm.repository?.state ?: remember { MutableStateFlow(RepoState()) }).collectAsState()
    val historic by vm.historic.collectAsState()
    val settings by vm.settings.settings.collectAsState()
    val selectedWindow by vm.selectedWindow.collectAsState()
    val userRefreshing by vm.userRefreshing.collectAsState()
    val phoneState by vm.phoneState.collectAsState()
    val drafts by vm.draftList.collectAsState()
    val view = historic ?: repoState.view
    val windows = remember(view, settings.sort, phoneState) { visibleTabs(view?.tabs.orEmpty(), phoneState).windows(settings.sort) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val actions = rememberTabActions(vm, snackbar, phoneState)

    val windowIndex = windows.indexOfFirst { it.id == selectedWindow }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = windowIndex) { windows.size }
    LaunchedEffect(windows, selectedWindow) {
        if (windows.isNotEmpty() && pager.currentPage != windowIndex) pager.scrollToPage(windowIndex)
    }
    LaunchedEffect(pager, windows) {
        snapshotFlow { pager.currentPage }.collect { page -> windows.getOrNull(page)?.let { vm.selectWindow(it.id) } }
    }
    val copy: (Tab) -> Unit = { tab ->
        copyText(ctx, tab.title, tab.text, settings.sensitiveClipboard)
        scope.launch { snackbar.showSnackbar(ctx.getString(R.string.copied, tab.text.length)) }
    }

    Scaffold(
        containerColor = p.editor,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                Bar(
                    leading = {
                        if (view != null && historic == null) SourceIndicator(view.origin, view.at, Modifier.padding(start = 8.dp))
                        else Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = p.text, modifier = Modifier.padding(start = 8.dp))
                    },
                    actions = {
                        BarIcon(R.drawable.ic_search, stringResource(R.string.search)) { vm.open(Screen.Overview) }
                        BarIcon(R.drawable.ic_add, stringResource(R.string.new_tab)) { vm.newTab() }
                        BarIcon(R.drawable.ic_history, stringResource(R.string.history)) { vm.loadHistory(); vm.open(Screen.History) }
                        BarIcon(R.drawable.ic_settings, stringResource(R.string.settings)) { vm.open(Screen.Settings) }
                    },
                )
                historic?.let { h ->
                    Row(
                        Modifier.fillMaxWidth().background(p.orange.copy(alpha = 0.18f)).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.viewing_copy, formatDateTime(ctx, h.at)), style = MaterialTheme.typography.labelLarge, color = p.text, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::backToNow) { Text(stringResource(R.string.back_to_now), color = p.blue) }
                    }
                }
                if (drafts.isNotEmpty() && historic == null) {
                    Text(
                        stringResource(R.string.drafts_n, drafts.size), style = MaterialTheme.typography.labelLarge, color = p.blue,
                        modifier = Modifier.fillMaxWidth().background(p.chrome).clickable { vm.open(Screen.Drafts) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (windows.size > 1) WindowTabs(windows, pager.currentPage) { i -> scope.launch { pager.animateScrollToPage(i) } }
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = userRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            val err = repoState.error
            when {
                err is KeyMismatchException || err is AuthRejectedException ->
                    Message(stringResource(R.string.key_mismatch), stringResource(R.string.scan_new_qr)) { vm.open(Screen.Pair) }
                view == null && err != null ->
                    Message(stringResource(R.string.no_connection), stringResource(R.string.retry), vm::refresh)
                view == null -> Message(stringResource(R.string.loading), null) {}
                windows.isEmpty() -> Message(stringResource(R.string.empty), null) {}
                // In the tab-bar layout, inner swipes change tabs; windows change by tapping.
                else -> HorizontalPager(
                    pager,
                    key = { windows[it].id },
                    userScrollEnabled = settings.layout == Layout.WINDOWS_LIST,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val w = windows[page]
                    when (settings.layout) {
                        Layout.WINDOWS_LIST -> WindowList(w, phoneState, actions, onOpen = { vm.openTab(it.id, w.id) }, onCopy = copy)
                        Layout.WINDOWS_TABS -> WindowTabsReader(vm, w, settings, phoneState, copy)
                    }
                }
            }
        }
    }
}

/** Window name (project or folder) or "Window N". */
@Composable
fun windowLabel(w: WindowGroup, number: Int): String =
    w.name.ifBlank { stringResource(R.string.window_n, number) }

@Composable
private fun WindowTabs(windows: List<WindowGroup>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val state = rememberLazyListState()
    LaunchedEffect(selected) { state.animateScrollToItem((selected - 1).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().background(p.chrome),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        itemsIndexed(windows, key = { _, w -> w.id }) { i, w ->
            val isSel = i == selected
            Column(
                Modifier.clickable { onSelect(i) }.padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.padding(top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        windowLabel(w, i + 1), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal),
                        color = if (isSel) p.text else p.dim,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        w.tabs.size.toString(), style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) p.editor else p.dim,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (isSel) p.blue else p.rule).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
                Box(Modifier.height(3.dp).width(32.dp).background(if (isSel) p.blue else p.chrome))
            }
        }
    }
}

@Composable
private fun WindowList(w: WindowGroup, state: PhoneState, actions: TabActions, onOpen: (Tab) -> Unit, onCopy: (Tab) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
        items(w.tabs, key = { it.id }) { tab ->
            TabListRow(tab, isPrivate(tab, state), onOpen = { onOpen(tab) }, onCopy = { onCopy(tab) }, actions = actions)
        }
    }
}

/** Second level as in Sublime: the window's tab bar, the text and the bottom bar. */
@Composable
private fun WindowTabsReader(vm: AppViewModel, w: WindowGroup, settings: Settings, state: PhoneState, onCopy: (Tab) -> Unit) {
    val selected by vm.selectedTab.collectAsState()
    val index = w.tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = index) { w.tabs.size }
    val scope = rememberCoroutineScope()
    SyncSelection(pager, w.tabs, index) { vm.select(it) }
    var finding by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    val current = w.tabs.getOrNull(pager.currentPage)
    Column(Modifier.fillMaxSize()) {
        TabStrip(w.tabs, pager.currentPage, title = { maskedTitle(it, state) }) { i -> scope.launch { pager.animateScrollToPage(i) } }
        HorizontalPager(pager, key = { w.tabs[it].id }, modifier = Modifier.weight(1f)) { page ->
            TabText(w.tabs[page], settings, if (finding) find else "")
        }
        if (current != null) BottomInfo(
            tab = current, finding = finding, find = find, onFind = { find = it },
            onToggleFind = { finding = !finding; if (!finding) find = "" },
            onCopy = { onCopy(current) },
            onEdit = { vm.edit(current) },
        )
    }
}

/** Keeps a tab pager on the selected tab across live updates, and reports swipes. */
@Composable
internal fun SyncSelection(pager: PagerState, tabs: List<Tab>, index: Int, onSelect: (Long) -> Unit) {
    LaunchedEffect(tabs, index) {
        if (tabs.isNotEmpty() && pager.currentPage != index) pager.scrollToPage(index)
    }
    LaunchedEffect(pager, tabs) {
        snapshotFlow { pager.currentPage }.collect { page -> tabs.getOrNull(page)?.let { onSelect(it.id) } }
    }
}

