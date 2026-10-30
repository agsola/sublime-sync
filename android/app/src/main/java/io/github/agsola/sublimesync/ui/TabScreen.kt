package io.github.agsola.sublimesync.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.data.RepoState
import io.github.agsola.sublimesync.settings.windows
import io.github.agsola.sublimesync.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A tab's full text, opened from the list; swipe to the window's other tabs. */
@Composable
fun TabScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val repoState by (vm.repository?.state ?: remember { MutableStateFlow(RepoState()) }).collectAsState()
    val historic by vm.historic.collectAsState()
    val settings by vm.settings.settings.collectAsState()
    val selected by vm.selectedTab.collectAsState()
    val selectedWindow by vm.selectedWindow.collectAsState()
    val window = remember(historic, repoState.view, settings.sort, selectedWindow) {
        (historic ?: repoState.view)?.tabs.orEmpty().windows(settings.sort).firstOrNull { it.id == selectedWindow }
    }
    val tabs = window?.tabs.orEmpty()
    val index = tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = index) { tabs.size }
    SyncSelection(pager, tabs, index) { vm.select(it) }
    val current = tabs.getOrNull(pager.currentPage)
    var finding by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Scaffold(
        containerColor = p.editor,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { BackBar(current?.title ?: "", onBack = { vm.back() }) },
        bottomBar = {
            if (current != null) BottomInfo(
                tab = current, finding = finding, find = find, onFind = { find = it },
                onToggleFind = { finding = !finding; if (!finding) find = "" },
                onCopy = {
                    copyText(ctx, current.title, current.text, settings.sensitiveClipboard)
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.copied, current.text.length)) }
                },
            )
        },
    ) { pad ->
        HorizontalPager(pager, key = { tabs[it].id }, modifier = Modifier.padding(pad).fillMaxSize()) { page ->
            TabText(tabs[page], settings, if (finding) find else "")
        }
    }
}
