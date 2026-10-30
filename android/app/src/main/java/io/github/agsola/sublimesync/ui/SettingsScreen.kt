package io.github.agsola.sublimesync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.BuildConfig
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.Screen
import io.github.agsola.sublimesync.settings.Layout
import io.github.agsola.sublimesync.settings.SettingsStore
import io.github.agsola.sublimesync.settings.Sources
import io.github.agsola.sublimesync.settings.SortOrder
import io.github.agsola.sublimesync.ui.theme.Atkinson
import io.github.agsola.sublimesync.ui.theme.JetBrainsMono
import io.github.agsola.sublimesync.ui.theme.LocalPalette

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val s by vm.settings.settings.collectAsState()
    var confirmUnpair by remember { mutableStateOf(false) }

    Scaffold(containerColor = p.editor, topBar = { BackBar(stringResource(R.string.settings), onBack = { vm.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Section(stringResource(R.string.layout_title), first = true)
            Choice(stringResource(R.string.layout_list), stringResource(R.string.layout_list_hint), s.layout == Layout.WINDOWS_LIST) {
                vm.settings.update { it.copy(layout = Layout.WINDOWS_LIST) }
            }
            Choice(stringResource(R.string.layout_tabs), stringResource(R.string.layout_tabs_hint), s.layout == Layout.WINDOWS_TABS) {
                vm.settings.update { it.copy(layout = Layout.WINDOWS_TABS) }
            }

            Section(stringResource(R.string.sort_title))
            Choice(stringResource(R.string.sort_sublime), stringResource(R.string.sort_sublime_hint), s.sort == SortOrder.SUBLIME) {
                vm.settings.update { it.copy(sort = SortOrder.SUBLIME) }
            }
            Choice(stringResource(R.string.sort_modified), stringResource(R.string.sort_modified_hint), s.sort == SortOrder.MODIFIED) {
                vm.settings.update { it.copy(sort = SortOrder.MODIFIED) }
            }

            Section(stringResource(R.string.text_title))
            Choice(stringResource(R.string.font_mono), null, s.mono) { vm.settings.update { it.copy(mono = true) } }
            Choice(stringResource(R.string.font_sans), null, !s.mono) { vm.settings.update { it.copy(mono = false) } }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.text_size), style = MaterialTheme.typography.bodyLarge, color = p.text)
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = s.textSize.toFloat(),
                    onValueChange = { v -> vm.settings.update { it.copy(textSize = v.toInt()) } },
                    valueRange = SettingsStore.TEXT_SIZES.first.toFloat()..SettingsStore.TEXT_SIZES.last.toFloat(),
                    steps = SettingsStore.TEXT_SIZES.last - SettingsStore.TEXT_SIZES.first - 1,
                    colors = SliderDefaults.colors(thumbColor = p.blue, activeTrackColor = p.blue, inactiveTrackColor = p.rule, activeTickColor = p.editor, inactiveTickColor = p.dim),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.text_sample),
                style = TextStyle(fontFamily = if (s.mono) JetBrainsMono else Atkinson, fontSize = s.textSize.sp, lineHeight = (s.textSize * 1.55f).sp),
                color = p.text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            Section(stringResource(R.string.tabs_section))
            val hiddenCount = vm.phoneState.collectAsState().value.entries.count { it.value.hidden }
            val draftCount = vm.draftList.collectAsState().value.size
            Action(stringResource(R.string.hidden_tabs_n, hiddenCount), stringResource(R.string.hidden_tabs_hint)) { vm.open(Screen.Hidden) }
            Action(stringResource(R.string.drafts_n, draftCount), stringResource(R.string.drafts_hint)) { vm.open(Screen.Drafts) }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sync_every), style = MaterialTheme.typography.bodyLarge, color = p.text)
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = s.syncSeconds.toFloat(),
                    onValueChange = { v -> vm.settings.update { it.copy(syncSeconds = v.toInt()) } },
                    valueRange = SettingsStore.SYNC_SECONDS.first.toFloat()..SettingsStore.SYNC_SECONDS.last.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = p.blue, activeTrackColor = p.blue, inactiveTrackColor = p.rule, activeTickColor = p.editor, inactiveTickColor = p.dim),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.seconds_n, s.syncSeconds), style = MaterialTheme.typography.labelLarge, color = p.text)
            }

            Section(stringResource(R.string.sources_title))
            Choice(stringResource(R.string.sources_both), stringResource(R.string.sources_both_hint), s.sources == Sources.BOTH) {
                vm.settings.update { it.copy(sources = Sources.BOTH) }
            }
            Choice(stringResource(R.string.sources_mac), stringResource(R.string.sources_mac_hint), s.sources == Sources.MAC_ONLY) {
                vm.settings.update { it.copy(sources = Sources.MAC_ONLY) }
            }
            Choice(stringResource(R.string.sources_server), stringResource(R.string.sources_server_hint), s.sources == Sources.SERVER_ONLY) {
                vm.settings.update { it.copy(sources = Sources.SERVER_ONLY) }
            }

            Section(stringResource(R.string.privacy_title))
            Toggle(stringResource(R.string.secure_screen), stringResource(R.string.secure_screen_hint), s.secureScreen) { v ->
                vm.settings.update { it.copy(secureScreen = v) }
            }
            Toggle(stringResource(R.string.sensitive_clip), stringResource(R.string.sensitive_clip_hint), s.sensitiveClipboard) { v ->
                vm.settings.update { it.copy(sensitiveClipboard = v) }
            }

            Section(stringResource(R.string.pairing_title))
            Action(stringResource(R.string.scan_new_qr), stringResource(R.string.scan_new_qr_hint)) { vm.open(Screen.Pair) }
            Action(stringResource(R.string.unpair), stringResource(R.string.unpair_hint), danger = true) { confirmUnpair = true }

            Spacer(Modifier.height(24.dp))
            Text("sublime-sync ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = p.dim,
                modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.unpair)) },
            text = { Text(stringResource(R.string.unpair_hint)) },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; vm.unpair() }) { Text(stringResource(R.string.unpair), color = p.red) } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String, first: Boolean = false) {
    val p = LocalPalette.current
    Column {
        if (!first) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = p.rule)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = p.blue, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
    }
}

@Composable
private fun Choice(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = p.blue, unselectedColor = p.dim))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = p.text)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = p.dim)
        }
    }
}

@Composable
private fun Toggle(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = p.text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = p.dim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = p.blue, checkedThumbColor = p.editor, uncheckedTrackColor = p.rule, uncheckedThumbColor = p.dim, uncheckedBorderColor = p.dim),
        )
    }
}

@Composable
private fun Action(label: String, hint: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) p.red else p.text)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = p.dim)
    }
}
