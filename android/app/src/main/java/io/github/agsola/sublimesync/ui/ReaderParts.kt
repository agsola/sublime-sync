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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.Screen
import io.github.agsola.sublimesync.crypto.KeyMismatchException
import io.github.agsola.sublimesync.data.AuthRejectedException
import io.github.agsola.sublimesync.data.RepoState
import io.github.agsola.sublimesync.model.Tab
import io.github.agsola.sublimesync.settings.Settings
import io.github.agsola.sublimesync.settings.sortedFor
import io.github.agsola.sublimesync.ui.theme.Atkinson
import io.github.agsola.sublimesync.ui.theme.JetBrainsMono
import io.github.agsola.sublimesync.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Sublime-like strip: the selected tab merges into the text area below. */
@Composable
internal fun TabStrip(tabs: List<Tab>, selected: Int, title: (Tab) -> String = { it.title }, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val state = rememberLazyListState()
    LaunchedEffect(selected) { state.animateScrollToItem((selected - 1).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().background(p.chrome),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(tabs, key = { _, t -> t.id }) { i, tab ->
            val newWindow = i > 0 && tabs[i - 1].window != tab.window
            Row(verticalAlignment = Alignment.Bottom) {
                if (newWindow) Box(Modifier.padding(horizontal = 6.dp, vertical = 10.dp).width(1.dp).height(20.dp).background(p.rule))
                val isSel = i == selected
                Row(
                    Modifier
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(if (isSel) p.editor else p.chrome)
                        .clickable { onSelect(i) }
                        .height(40.dp)
                        .widthIn(max = 180.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title(tab), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal),
                        color = if (isSel) p.text else p.dim,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (tab.dirty) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(p.orange))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TabText(tab: Tab, settings: Settings, find: String) {
    val p = LocalPalette.current
    val chunks = remember(tab.text) { tab.text.lines().chunked(CHUNK_LINES).map { it.joinToString("\n") } }
    val pattern = remember(find) { if (find.isBlank()) null else Regex(Regex.escape(find), RegexOption.IGNORE_CASE) }
    val style = TextStyle(
        fontFamily = if (settings.mono) JetBrainsMono else Atkinson,
        fontSize = settings.textSize.sp,
        lineHeight = (settings.textSize * 1.55f).sp,
        color = p.text,
    )
    Column(Modifier.fillMaxSize()) {
        if (tab.truncated) {
            Text(
                stringResource(R.string.truncated), style = MaterialTheme.typography.bodySmall, color = p.text,
                modifier = Modifier.fillMaxWidth().background(p.orange.copy(alpha = 0.18f)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp)) {
                items(chunks.size) { i ->
                    val chunk = chunks[i]
                    val text = remember(chunk, pattern, p.highlight) {
                        buildAnnotatedString {
                            if (pattern == null) append(chunk) else {
                                var last = 0
                                pattern.findAll(chunk).forEach { m ->
                                    append(chunk.substring(last, m.range.first))
                                    withStyle(SpanStyle(background = p.highlight)) { append(m.value) }
                                    last = m.range.last + 1
                                }
                                append(chunk.substring(last))
                            }
                        }
                    }
                    Text(text, style = style)
                }
            }
        }
    }
}

@Composable
internal fun BottomInfo(
    tab: Tab,
    finding: Boolean,
    find: String,
    onFind: (String) -> Unit,
    onToggleFind: () -> Unit,
    onCopy: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.chrome).navigationBarsPadding()) {
        if (finding) {
            val count = remember(tab.text, find) {
                if (find.isBlank()) 0 else Regex(Regex.escape(find), RegexOption.IGNORE_CASE).findAll(tab.text).count()
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = find, onValueChange = onFind, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
                    cursorBrush = SolidColor(p.blue),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(p.editor).padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (find.isEmpty()) Text(stringResource(R.string.find), style = MaterialTheme.typography.bodyLarge, color = p.dim)
                        inner()
                    },
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.matches, count), style = MaterialTheme.typography.labelMedium, color = p.dim)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val modified = relative(tab.modified)
                Text(
                    if (modified.isEmpty()) stringResource(R.string.modified_unknown) else stringResource(R.string.modified_ago, modified),
                    style = MaterialTheme.typography.labelLarge, color = p.text,
                )
                Text(
                    stringResource(if (tab.dirty) R.string.unsaved_chars else R.string.saved_chars, tab.text.length),
                    style = MaterialTheme.typography.labelSmall, color = if (tab.dirty) p.orange else p.dim,
                )
            }
            BarIcon(R.drawable.ic_search, stringResource(R.string.find), onToggleFind)
            if (onEdit != null) BarIcon(R.drawable.ic_edit, stringResource(R.string.edit), onEdit)
            Button(
                onClick = onCopy,
                colors = ButtonDefaults.buttonColors(containerColor = p.blue, contentColor = p.editor),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_copy), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.copy_all), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun Message(text: String, action: String?, onAction: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = p.dim)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAction) { Text(action, color = p.blue, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

private const val CHUNK_LINES = 200
