package io.github.agsola.sublimesync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.data.RepoState
import io.github.agsola.sublimesync.data.maskedTitle
import io.github.agsola.sublimesync.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow

/** Hidden tabs that are still open in Sublime, with a way to show them again. */
@Composable
fun HiddenScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val repoState by (vm.repository?.state ?: remember { MutableStateFlow(RepoState()) }).collectAsState()
    val state by vm.phoneState.collectAsState()
    val hiddenUids = state.entries.filter { it.value.hidden }.keys
    val open = repoState.view?.tabs.orEmpty().filter { it.uid in hiddenUids }
    val gone = hiddenUids - open.map { it.uid }.toSet()
    Scaffold(containerColor = p.editor, topBar = { BackBar(stringResource(R.string.hidden_tabs), onBack = { vm.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (hiddenUids.isEmpty()) item {
                Text(stringResource(R.string.hidden_empty), style = MaterialTheme.typography.bodyLarge, color = p.dim, modifier = Modifier.padding(24.dp))
            }
            items(open, key = { it.uid }) { tab ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(maskedTitle(tab, state), style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(relative(tab.modified), style = MaterialTheme.typography.labelSmall, color = p.dim)
                    }
                    TextButton(onClick = { vm.setHidden(tab.uid, false) }) { Text(stringResource(R.string.show_again), color = p.blue) }
                }
            }
            if (gone.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hidden_gone_n, gone.size), style = MaterialTheme.typography.bodyMedium, color = p.dim, modifier = Modifier.weight(1f))
                    TextButton(onClick = { gone.forEach { vm.setHidden(it, false) } }) { Text(stringResource(R.string.forget), color = p.blue) }
                }
            }
        }
    }
}
