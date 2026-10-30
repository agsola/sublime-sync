package io.github.agsola.sublimesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.data.RepoState
import io.github.agsola.sublimesync.model.Tab
import io.github.agsola.sublimesync.settings.SortOrder
import io.github.agsola.sublimesync.settings.sortedFor
import io.github.agsola.sublimesync.ui.theme.JetBrainsMono
import io.github.agsola.sublimesync.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Every tab as a searchable list; tapping one opens it in the reader. */
@Composable
fun OverviewScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val repoState by (vm.repository?.state ?: remember { MutableStateFlow(RepoState()) }).collectAsState()
    val historic by vm.historic.collectAsState()
    val settings by vm.settings.settings.collectAsState()
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val tabs = remember(historic, repoState.view, settings.sort, query) {
        (historic ?: repoState.view)?.tabs.orEmpty().sortedFor(settings.sort).filter {
            query.isBlank() || it.title.contains(query, true) || it.text.contains(query, true)
        }
    }
    val byWindow = settings.sort == SortOrder.SUBLIME && tabs.map { it.window }.distinct().size > 1
    val windowNumber = remember(tabs) { tabs.map { it.window }.distinct().withIndex().associate { it.value to it.index + 1 } }

    Scaffold(
        containerColor = p.editor,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BackBar(stringResource(R.string.all_tabs), onBack = { vm.back() })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
                cursorBrush = SolidColor(p.blue),
                modifier = Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(8.dp))
                    .background(p.chrome).padding(horizontal = 14.dp, vertical = 12.dp).focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(stringResource(R.string.search), style = MaterialTheme.typography.bodyLarge, color = p.dim)
                    inner()
                },
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                if (tabs.isEmpty()) item {
                    Text(stringResource(if (query.isBlank()) R.string.empty else R.string.no_matches),
                        style = MaterialTheme.typography.bodyLarge, color = p.dim, modifier = Modifier.padding(24.dp))
                }
                items(tabs, key = { "${it.window}-${it.id}" }) { tab ->
                    val first = tabs.first { it.window == tab.window } === tab
                    if (byWindow && first) {
                        Text(
                            tab.windowName.ifBlank { stringResource(R.string.window_n, windowNumber[tab.window] ?: 1) },
                            style = MaterialTheme.typography.titleSmall, color = p.blue,
                            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }
                    TabListRow(tab, onOpen = { vm.openTab(tab.id, tab.window) }, onCopy = {
                        copyText(ctx, tab.title, tab.text, settings.sensitiveClipboard)
                        scope.launch { snackbar.showSnackbar(ctx.getString(R.string.copied, tab.text.length)) }
                    })
                }
            }
        }
    }
}

