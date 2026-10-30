package io.github.agsola.sublimesync.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.ui.theme.LocalPalette

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val items by vm.history.collectAsState()
    val ctx = LocalContext.current
    val byDay = items.groupBy {
        DateUtils.formatDateTime(ctx, it.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR)
    }
    Scaffold(containerColor = p.editor, topBar = { BackBar(stringResource(R.string.history), onBack = { vm.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (items.isEmpty()) item {
                Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = p.dim, modifier = Modifier.padding(24.dp))
            }
            byDay.forEach { (day, list) ->
                item(key = "d$day") {
                    Text(day, style = MaterialTheme.typography.titleSmall, color = p.blue,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
                }
                items(list, key = { it.seq }) { item ->
                    Text(
                        DateUtils.formatDateTime(ctx, item.at, DateUtils.FORMAT_SHOW_TIME),
                        style = MaterialTheme.typography.bodyLarge, color = p.text,
                        modifier = Modifier.fillMaxWidth().clickable { vm.openHistoric(item.seq) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            if (items.isNotEmpty()) item {
                TextButton(onClick = { vm.loadHistory(more = true) }, modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.load_more), color = p.blue)
                }
            }
        }
    }
}
