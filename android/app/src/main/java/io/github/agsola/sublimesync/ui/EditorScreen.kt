package io.github.agsola.sublimesync.ui

import androidx.compose.foundation.background
import io.github.agsola.sublimesync.data.firstLine
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agsola.sublimesync.AppViewModel
import io.github.agsola.sublimesync.R
import io.github.agsola.sublimesync.data.Draft
import io.github.agsola.sublimesync.data.DraftStatus
import io.github.agsola.sublimesync.ui.editor.AutoIndent
import io.github.agsola.sublimesync.ui.editor.EditHistory
import io.github.agsola.sublimesync.ui.editor.FlushThrottle
import io.github.agsola.sublimesync.ui.editor.replaceAll
import io.github.agsola.sublimesync.ui.theme.JetBrainsMono
import io.github.agsola.sublimesync.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Plain-text editor for a phone draft; every change is kept on the phone. */
@Composable
fun EditorScreen(vm: AppViewModel, draftId: String) {
    val p = LocalPalette.current
    val settings by vm.settings.settings.collectAsState()
    val drafts by vm.draftList.collectAsState()
    val draft = drafts.firstOrNull { it.id == draftId } ?: vm.draft(draftId)
    if (draft == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    var value by remember(draftId) { mutableStateOf(TextFieldValue(draft.text, TextRange(0))) }
    val history = remember(draftId) { EditHistory(value) }
    var finding by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) } // recomposes undo/redo buttons
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    // A new, empty tab opens ready to type.
    LaunchedEffect(draftId) {
        if (draft.text.isEmpty()) runCatching { focus.requestFocus() }
    }

    val throttle = remember(draftId) { FlushThrottle(500) }

    fun set(v: TextFieldValue, record: Boolean = true) {
        value = v
        val now = System.currentTimeMillis()
        if (record) history.record(v, now)
        version++
        vm.updateDraft(draftId, v.text)
        // Written to disk at least every half second, even while typing non-stop.
        if (throttle.due(now)) vm.flushDrafts()
    }

    // Write to disk half a second after the last keystroke, and when leaving.
    LaunchedEffect(value.text) {
        delay(500)
        vm.flushDrafts()
    }
    DisposableEffect(draftId) {
        onDispose {
            vm.flushDrafts()
            vm.syncNow()
        }
    }

    Scaffold(
        containerColor = p.editor,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BackBar("✎ " + draft.title.ifBlank { firstLine(value.text) }.ifBlank { stringResource(R.string.new_tab_title) }, onBack = { vm.back() }) {
                version.let { }
                if (history.canUndo) BarIcon(R.drawable.ic_undo, stringResource(R.string.undo)) { history.undo()?.let { set(it, record = false) } }
                if (history.canRedo) BarIcon(R.drawable.ic_redo, stringResource(R.string.redo)) { history.redo()?.let { set(it, record = false) } }
                BarIcon(R.drawable.ic_search, stringResource(R.string.find_replace)) { finding = !finding }
                BarIcon(R.drawable.ic_copy, stringResource(R.string.copy_all)) {
                    copyText(ctx, draft.title, value.text, settings.sensitiveClipboard)
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.copied, value.text.length)) }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().navigationBarsPadding()) {
            if (finding) {
                FindReplaceBar(
                    find = find, replacement = replacement,
                    onFind = { find = it }, onReplacement = { replacement = it },
                    onNext = {
                        val from = value.selection.end
                        val i = value.text.indexOf(find, from, ignoreCase = true).takeIf { it >= 0 }
                            ?: value.text.indexOf(find, 0, ignoreCase = true)
                        if (find.isNotEmpty() && i >= 0) value = value.copy(selection = TextRange(i, i + find.length))
                    },
                    onReplace = {
                        val sel = value.text.substring(value.selection.min, value.selection.max)
                        if (find.isNotEmpty() && sel.equals(find, ignoreCase = true)) {
                            val t = value.text.replaceRange(value.selection.min, value.selection.max, replacement)
                            set(TextFieldValue(t, TextRange(value.selection.min + replacement.length)))
                        }
                    },
                    onReplaceAll = {
                        val (t, n) = replaceAll(value.text, find, replacement, ignoreCase = true)
                        if (n > 0) set(TextFieldValue(t, TextRange(0)))
                        scope.launch { snackbar.showSnackbar(ctx.getString(R.string.replaced, n)) }
                    },
                )
            }
            val style = TextStyle(fontFamily = JetBrainsMono, fontSize = settings.textSize.sp, lineHeight = (settings.textSize * 1.55f).sp, color = p.text)
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val gutter = gutterWidth(value.text, style)
            // Gutter and text scroll together, like in Sublime; the gutter's
            // background is drawn behind the whole area so it reaches the bottom.
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth()
                    .drawBehind { drawRect(p.chrome.copy(alpha = 0.5f), size = Size(gutter.toPx(), size.height)) },
            ) {
            val viewport = maxHeight
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                LineNumbers(value.text, layout, style, gutter)
                BasicTextField(
                    value = value,
                    onValueChange = { new ->
                        if (new.text == value.text) value = new else set(AutoIndent.apply(value, new))
                    },
                    textStyle = style,
                    cursorBrush = SolidColor(p.blue),
                    onTextLayout = { layout = it },
                    // Plain text editor: no autocorrect or automatic capitals.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
                    // The whole area is the text field, so a tap anywhere starts typing.
                    modifier = Modifier.weight(1f).heightIn(min = viewport).focusRequester(focus)
                        .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 48.dp),
                )
            }
            }
            StatusLine(draft, value.text)
        }
    }
}

/** Logical line numbers aligned with where each line starts after wrapping. */
@Composable
private fun gutterWidth(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val digits = (text.count { it == '\n' } + 1).toString().length.coerceAtLeast(2)
    return with(LocalDensity.current) { measurer.measure("0".repeat(digits), numberStyle(style)).size.width.toDp() + 20.dp }
}

@Composable
private fun numberStyle(style: TextStyle) = style.copy(color = LocalPalette.current.dim.copy(alpha = 0.7f), fontSize = style.fontSize * 0.8f)

@Composable
private fun LineNumbers(text: String, layout: TextLayoutResult?, style: TextStyle, width: Dp) {
    val measurer = rememberTextMeasurer()
    val numberStyle = numberStyle(style)
    val height = with(LocalDensity.current) { (layout?.size?.height ?: 0).toDp() }
    Canvas(Modifier.width(width).height(height + 60.dp).padding(top = 12.dp)) {
        val l = layout ?: return@Canvas
        var offset = 0
        var n = 1
        while (offset <= text.length && offset <= l.layoutInput.text.length) {
            val line = l.getLineForOffset(offset)
            val label = measurer.measure(n.toString(), numberStyle)
            val y = l.getLineTop(line) + (l.getLineBottom(line) - l.getLineTop(line) - label.size.height) / 2
            drawText(label, topLeft = Offset(size.width - label.size.width - 10.dp.toPx(), y))
            val next = text.indexOf('\n', offset)
            if (next < 0) break
            offset = next + 1
            n++
        }
    }
}

@Composable
private fun StatusLine(draft: Draft, text: String) {
    val p = LocalPalette.current
    val sent = relative(draft.sentAt)
    val (label, color) = when (draft.status) {
        DraftStatus.SAVED_LOCAL -> stringResource(R.string.st_saved_local) to p.dim
        DraftStatus.SENT -> stringResource(R.string.st_sent, sent) to p.blue
        DraftStatus.APPLIED -> stringResource(R.string.st_applied) to p.green
        DraftStatus.CONFLICT -> stringResource(R.string.st_conflict) to p.orange
        DraftStatus.PENDING_SUBLIME -> stringResource(R.string.st_pending) to p.orange
        DraftStatus.OFFLINE -> stringResource(R.string.st_offline) to p.orange
        DraftStatus.ERROR -> stringResource(R.string.st_error, draft.reason) to p.red
    }
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    Row(
        Modifier.fillMaxWidth().background(p.chrome).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.lines_chars, lines, text.length), style = MaterialTheme.typography.labelSmall, color = p.dim)
    }
}

@Composable
private fun FindReplaceBar(
    find: String, replacement: String,
    onFind: (String) -> Unit, onReplacement: (String) -> Unit,
    onNext: () -> Unit, onReplace: () -> Unit, onReplaceAll: () -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.chrome).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(find, onFind, stringResource(R.string.find), Modifier.weight(1f))
            TextButton(onClick = onNext) { Text(stringResource(R.string.next), color = p.blue) }
        }
        Spacer(Modifier.width(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(replacement, onReplacement, stringResource(R.string.replace_with), Modifier.weight(1f))
            TextButton(onClick = onReplace) { Text(stringResource(R.string.replace), color = p.blue) }
            TextButton(onClick = onReplaceAll) { Text(stringResource(R.string.replace_all), color = p.blue) }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier) {
    val p = LocalPalette.current
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
        cursorBrush = SolidColor(p.blue),
        modifier = modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(p.editor).padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = p.dim)
            inner()
        },
    )
}
