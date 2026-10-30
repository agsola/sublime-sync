package io.github.agsola.sublimesync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.Screen
import io.github.agsola.sublimesync.data.Draft
import io.github.agsola.sublimesync.data.MASK
import io.github.agsola.sublimesync.data.firstLine
import io.github.agsola.sublimesync.data.isPrivateDraft
import io.github.agsola.sublimesync.ui.theme.JetBrainsMono
import io.github.agsola.sublimesync.ui.theme.LocalPalette

@Composable
fun DraftsScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val drafts by vm.draftList.collectAsState()
    val phoneState by vm.phoneState.collectAsState()
    var deleting by remember { mutableStateOf<Draft?>(null) }
    Scaffold(containerColor = p.editor, topBar = { BackBar(stringResource(R.string.drafts), onBack = { vm.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (drafts.isEmpty()) item {
                Text(stringResource(R.string.drafts_empty), style = MaterialTheme.typography.bodyLarge, color = p.dim, modifier = Modifier.padding(24.dp))
            }
            items(drafts, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.open(Screen.Editor(d.id)) }.padding(start = 20.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val private = isPrivateDraft(d, phoneState)
                    Column(Modifier.weight(1f)) {
                        Text(if (private) MASK else d.title.ifBlank { firstLine(d.text) }.ifBlank { stringResource(R.string.new_tab_title) },
                            style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(relative(d.updatedAt), style = MaterialTheme.typography.labelSmall, color = p.dim)
                        Text(if (private) "$MASK $MASK $MASK" else previewOf(d.text, 2), maxLines = 2, overflow = TextOverflow.Ellipsis, color = p.dim,
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 19.sp))
                    }
                    TextButton(onClick = { deleting = d }) { Text(stringResource(R.string.delete_draft), color = p.red) }
                }
            }
        }
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_draft)) },
            text = { Text(stringResource(R.string.delete_draft_hint)) },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deleteDraft(d.id) }) { Text(stringResource(R.string.delete_draft), color = p.red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
