package io.github.agsola.sublimesync.ui

import android.content.ClipData
import io.github.agsola.sublimesync.ui.theme.JetBrainsMono
import io.github.agsola.sublimesync.model.Tab
import io.github.agsola.sublimesync.data.MASK
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.data.Origin
import io.github.agsola.sublimesync.ui.theme.LocalPalette
import kotlinx.coroutines.delay

/** "3 min ago", refreshed every 30 s; empty when unknown. */
@Composable
fun relative(at: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    if (at <= 0) return ""
    if (now - at < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.just_now)
    return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
fun originColor(origin: Origin): Color {
    val p = LocalPalette.current
    return when (origin) {
        Origin.MAC_TAILSCALE -> p.green
        Origin.SERVER_LIVE -> p.blue
        Origin.SERVER_COPY -> p.orange
        Origin.CACHE -> p.dim
    }
}

/** Where the data comes from and how fresh it is. */
@Composable
fun SourceIndicator(origin: Origin, at: Long, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val label = when (origin) {
        Origin.MAC_TAILSCALE -> stringResource(R.string.origin_mac)
        Origin.SERVER_LIVE -> stringResource(R.string.origin_server_live)
        Origin.SERVER_COPY -> stringResource(R.string.origin_server_copy_short)
        Origin.CACHE -> stringResource(R.string.origin_cache_short)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(originColor(origin)))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall, color = p.text)
            Text(stringResource(R.string.snapshot_from, relative(at)), style = MaterialTheme.typography.labelSmall, color = p.dim)
        }
    }
}

/** A plain top bar on the chrome color; [leading] fills the left side. */
@Composable
fun Bar(leading: @Composable () -> Unit, actions: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.chrome).statusBarsPadding().height(60.dp).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.weight(1f)) { leading() }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
fun BarIcon(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), description, tint = LocalPalette.current.dim)
    }
}

@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) = Bar(
    leading = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BarIcon(io.github.agsola.sublimesync.R.drawable.ic_back, stringResource(R.string.back), onBack)
            Text(title, style = MaterialTheme.typography.titleLarge, color = LocalPalette.current.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    },
    actions = actions,
)

/** Copies text; if [sensitive], Android hides it in the clipboard preview. */
fun copyText(ctx: Context, label: String, text: String, sensitive: Boolean) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
}

fun formatDateTime(ctx: Context, at: Long): String =
    DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

/** First non-blank lines, without scanning a multi-megabyte text. */
fun previewOf(text: String, lines: Int): String =
    text.take(2_000).lineSequence().map { it.trimEnd() }.dropWhile { it.isBlank() }.take(lines).joinToString("\n")

@Composable
fun TabListRow(tab: Tab, private: Boolean, onOpen: () -> Unit, onCopy: () -> Unit, actions: TabActions?) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, end = 0.dp, top = 12.dp, bottom = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (private) {
                    Icon(painterResource(R.drawable.ic_lock), null, tint = p.dim, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (private) MASK else displayTitle(tab), style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (tab.dirty) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(p.orange))
                }
                Spacer(Modifier.width(12.dp))
                Text(relative(tab.modified), style = MaterialTheme.typography.labelSmall, color = p.dim)
            }
            Spacer(Modifier.height(4.dp))
            val preview = remember(tab.text, tab.title, private) { if (private) "$MASK $MASK $MASK" else previewAfterTitle(tab.text, displayTitle(tab), 2) }
            Text(
                preview, maxLines = 2, overflow = TextOverflow.Ellipsis, color = p.dim,
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 19.sp),
            )
        }
        BarIcon(R.drawable.ic_copy, stringResource(R.string.copy), onCopy)
        if (actions != null) TabMenu(tab, private, actions)
    }
}

/** Preview lines, skipping the first one when it is already shown as the title. */
fun previewAfterTitle(text: String, title: String, lines: Int): String {
    // Lines with no letters or digits (separators like "-----") are decoration.
    val all = text.take(2_000).lineSequence().map { it.trimEnd() }.filter { l -> l.any { it.isLetterOrDigit() } }.toList()
    val rest = if (title.isNotBlank() && all.firstOrNull()?.trim()?.startsWith(title.trim()) == true) all.drop(1) else all
    return rest.take(lines).joinToString("\n")
}

/** A readable title: Sublime uses the first line, which may be only dashes. */
fun displayTitle(tab: Tab): String {
    if (tab.title.any { it.isLetterOrDigit() }) return tab.title
    return tab.text.take(2_000).lineSequence().map { it.trim() }.firstOrNull { l -> l.any { it.isLetterOrDigit() } }?.take(80)
        ?: tab.title.ifBlank { "untitled" }
}
