package io.github.agsola.sublimesync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.Screen
import io.github.agsola.sublimesync.model.PairPayload
import io.github.agsola.sublimesync.ui.theme.LocalPalette
import io.github.agsola.sublimesync.ui.theme.SublimeTheme
import java.net.URI

@Composable
fun App(vm: AppViewModel, onUnlock: () -> Unit, canAuthenticate: Boolean) {
    SublimeTheme {
        Surface(Modifier.fillMaxSize(), color = LocalPalette.current.editor) {
            val screen by vm.screen.collectAsState()
            when (screen) {
                Screen.Locked -> LockedScreen(onUnlock, canAuthenticate)
                Screen.Pair -> PairScreen(vm)
                Screen.Main -> MainScreen(vm)
                is Screen.Tab -> TabScreen(vm)
                Screen.Overview -> OverviewScreen(vm)
                Screen.History -> HistoryScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun LockedScreen(onUnlock: () -> Unit, canAuthenticate: Boolean) = Centered {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(24.dp))
    if (canAuthenticate) {
        Button(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
    } else {
        Text(stringResource(R.string.need_screen_lock), textAlign = TextAlign.Center)
    }
}

@Composable
private fun PairScreen(vm: AppViewModel) {
    var pending by remember { mutableStateOf<PairPayload?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val message by vm.pairMessage.collectAsState()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        try {
            pending = PairPayload.parse(raw)
            error = null
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
    }
    Centered {
        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineSmall)
        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.pair_body), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false).setPrompt(""))
        }) { Text(stringResource(R.string.scan_qr)) }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.bad_qr, it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        if (vm.repository != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.back() }) { Text(stringResource(R.string.cancel)) }
        }
    }
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.pair_title)) },
            text = { Text(stringResource(R.string.pair_confirm, URI(p.server).host ?: p.server)) },
            confirmButton = { TextButton(onClick = { pending = null; vm.pair(p) }) { Text(stringResource(R.string.pair_ok)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
