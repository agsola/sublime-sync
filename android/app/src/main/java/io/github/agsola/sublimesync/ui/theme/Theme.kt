package io.github.agsola.sublimesync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.agsola.sublimesync.R

/** Colors taken from Sublime Text 4's default "Mariana" scheme. */
@Immutable
data class Palette(
    val editor: Color,     // text area, selected tab
    val chrome: Color,     // tab strip, bars
    val text: Color,
    val dim: Color,        // secondary text
    val rule: Color,       // hairlines
    val blue: Color,       // primary action, live via server
    val green: Color,      // live from the Mac
    val orange: Color,     // unsaved dot, stale copy
    val red: Color,        // errors
    val highlight: Color,  // find matches
)

val MarianaDark = Palette(
    editor = Color(0xFF303841),
    chrome = Color(0xFF242A31),
    text = Color(0xFFD8DEE9),
    dim = Color(0xFFA6ACB9),
    rule = Color(0xFF3E4852),
    blue = Color(0xFF6699CC),
    green = Color(0xFF99C794),
    orange = Color(0xFFF9AE58),
    red = Color(0xFFEC5F66),
    highlight = Color(0xFF5C4A2E),
)

val MarianaLight = Palette(
    editor = Color(0xFFF7F9FB),
    chrome = Color(0xFFE2E7EC),
    text = Color(0xFF303841),
    dim = Color(0xFF5E6873),
    rule = Color(0xFFD0D7DE),
    blue = Color(0xFF3D6E9E),
    green = Color(0xFF3F7D3A),
    orange = Color(0xFFB8690F),
    red = Color(0xFFC0393F),
    highlight = Color(0xFFFBE3BC),
)

val LocalPalette = staticCompositionLocalOf { MarianaDark }

val Atkinson = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private fun ui(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.35f) =
    TextStyle(fontFamily = Atkinson, fontWeight = weight, fontSize = size.sp, lineHeight = (size * line).sp)

private val type = Typography(
    titleLarge = ui(22, FontWeight.Bold, 1.25f),
    titleMedium = ui(17, FontWeight.Bold),
    titleSmall = ui(15, FontWeight.Bold),
    bodyLarge = ui(17, line = 1.5f),
    bodyMedium = ui(15, line = 1.45f),
    bodySmall = ui(13),
    labelLarge = ui(15, FontWeight.Bold),
    labelMedium = ui(13),
    labelSmall = ui(12),
)

@Composable
fun SublimeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) MarianaDark else MarianaLight
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.blue, onPrimary = p.editor,
        secondary = p.green, tertiary = p.orange,
        background = p.editor, onBackground = p.text,
        surface = p.editor, onSurface = p.text,
        surfaceVariant = p.chrome, onSurfaceVariant = p.dim,
        surfaceContainer = p.chrome, surfaceContainerHigh = p.chrome, surfaceContainerLow = p.editor,
        outline = p.dim, outlineVariant = p.rule,
        error = p.red,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
