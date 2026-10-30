package io.github.agsola.sublimesync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.model.PhoneState
import io.github.agsola.sublimesync.model.Tab
import io.github.agsola.sublimesync.data.isPrivate
import io.github.agsola.sublimesync.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/** What can be done to a tab from any screen. */
class TabActions(
    val edit: (Tab) -> Unit,
    val hide: (Tab) -> Unit,
    val togglePrivate: (Tab) -> Unit,
    val close: (Tab) -> Unit,
)

/**
 * Tab actions wired to the view model: hiding offers "Undo" for a few
 * seconds, closing asks first and reports Sublime's answer.
 */
@Composable
fun rememberTabActions(vm: AppViewModel, snackbar: SnackbarHostState, state: PhoneState): TabActions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf<Tab?>(null) }
    closing?.let { tab ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.close_in_sublime)) },
            text = { Text(stringResource(R.string.close_confirm, tab.title)) },
            confirmButton = {
                TextButton(onClick = {
                    closing = null
                    vm.closeInSublime(tab) { r ->
                        val msg = when (r?.status) {
                            null -> ctx.getString(R.string.close_failed)
                            "applied" -> ctx.getString(R.string.close_ok)
                            "pending" -> ctx.getString(R.string.close_pending)
                            else -> ctx.getString(R.string.close_refused, r.reason)
                        }
                        scope.launch { snackbar.showSnackbar(msg) }
                    }
                }) { Text(stringResource(R.string.close_in_sublime), color = LocalPalette.current.red) }
            },
            dismissButton = { TextButton(onClick = { closing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    return remember(vm, state) {
        TabActions(
            edit = { vm.edit(it) },
            hide = { tab ->
                vm.setHidden(tab.uid, true)
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar(ctx.getString(R.string.hidden_toast), ctx.getString(R.string.undo), duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.setHidden(tab.uid, false)
                }
            },
            togglePrivate = { tab -> vm.setPrivate(tab.uid, !isPrivate(tab, state)) },
            close = { closing = it },
        )
    }
}

/** The ⋮ menu on a tab. Hide/private/close need a tab id from a 0.2+ agent. */
@Composable
fun TabMenu(tab: Tab, private: Boolean, actions: TabActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarIcon(R.drawable.ic_more, stringResource(R.string.more)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { open = false; actions.edit(tab) })
            if (tab.uid.isNotBlank()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.hide)) }, onClick = { open = false; actions.hide(tab) })
                DropdownMenuItem(
                    text = { Text(stringResource(if (private) R.string.make_public else R.string.make_private)) },
                    onClick = { open = false; actions.togglePrivate(tab) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.close_in_sublime), color = LocalPalette.current.red) },
                    onClick = { open = false; actions.close(tab) },
                )
            }
        }
    }
}
