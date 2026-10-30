package io.github.agsola.sublimesync.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Undo/redo for the editor; quick successive edits merge into one step. */
class EditHistory(initial: TextFieldValue, private val coalesceMs: Long = 1_000, private val max: Int = 200) {
    private val undo = ArrayDeque(listOf(initial))
    private val redo = ArrayDeque<TextFieldValue>()
    private var lastAt = Long.MIN_VALUE / 2

    val canUndo get() = undo.size > 1
    val canRedo get() = redo.isNotEmpty()

    fun record(value: TextFieldValue, atMs: Long) {
        if (value.text == undo.last().text) return
        if (atMs - lastAt < coalesceMs && undo.size > 1) undo.removeLast()
        undo.addLast(value)
        if (undo.size > max) undo.removeFirst()
        redo.clear()
        lastAt = atMs
    }

    fun undo(): TextFieldValue? {
        if (undo.size <= 1) return null
        redo.addLast(undo.removeLast())
        lastAt = Long.MIN_VALUE / 2
        return undo.last()
    }

    fun redo(): TextFieldValue? {
        val v = redo.removeLastOrNull() ?: return null
        undo.addLast(v)
        return v
    }
}

object AutoIndent {
    /** When exactly one newline was typed, repeats the previous line's indentation. */
    fun apply(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        val pos = new.selection.start
        if (new.text.length != old.text.length + 1 || !new.selection.collapsed || pos < 1 || new.text[pos - 1] != '\n') return new
        if (new.text.removeRange(pos - 1, pos) != old.text) return new
        val lineStart = new.text.lastIndexOf('\n', pos - 2) + 1
        val indent = new.text.substring(lineStart, pos - 1).takeWhile { it == ' ' || it == '\t' }
        if (indent.isEmpty()) return new
        return TextFieldValue(new.text.substring(0, pos) + indent + new.text.substring(pos), TextRange(pos + indent.length))
    }
}

/** Literal replace-all; returns the new text and how many were replaced. */
fun replaceAll(text: String, find: String, replacement: String, ignoreCase: Boolean): Pair<String, Int> {
    if (find.isEmpty()) return text to 0
    val re = Regex(Regex.escape(find), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
    var n = 0
    val out = re.replace(text) { n++; replacement }
    return out to n
}

/** Says when to write to disk while typing: at most every [intervalMs], even without pauses. */
class FlushThrottle(private val intervalMs: Long) {
    private var last = Long.MIN_VALUE / 2

    fun due(nowMs: Long): Boolean {
        if (nowMs - last < intervalMs) return false
        last = nowMs
        return true
    }
}
