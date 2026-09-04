package de.tieo.taplex

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Taplex's colours, set rather than taken.
 *
 * Dark by default, because the app is used over whatever someone is already reading, at
 * night, in a chat: a white screen between two dark ones is the wrong way round. The blue is
 * the blue of the circle the app draws over a word and the amber is the marker its entry
 * card uses, so the screen and the thing it does are the same app.
 */
object Ink {
    val Blue = Color(0xFF4C9AFF)
    val BlueDeep = Color(0xFF1F6FEB)
    val Amber = Color(0xFFFFC53D)
    val Ground = Color(0xFF0B0F16)
    val Raised = Color(0xFF141A24)
    val Line = Color(0xFF222B38)
    val Text = Color(0xFFE7ECF3)
    val Muted = Color(0xFF8B97A8)
    val Red = Color(0xFFFF6B5C)
}

private val Dark = darkColorScheme(
    primary = Ink.Blue,
    onPrimary = Color(0xFF04203F),
    primaryContainer = Color(0xFF13305C),
    onPrimaryContainer = Color(0xFFD7E7FF),
    secondary = Ink.Amber,
    onSecondary = Color(0xFF3A2A00),
    secondaryContainer = Color(0xFF1B2432),
    onSecondaryContainer = Ink.Text,
    background = Ink.Ground,
    onBackground = Ink.Text,
    surface = Ink.Ground,
    onSurface = Ink.Text,
    surfaceVariant = Ink.Raised,
    onSurfaceVariant = Ink.Muted,
    outline = Ink.Line,
    outlineVariant = Ink.Line,
    error = Ink.Red,
    errorContainer = Color(0xFF2A1512),
    onErrorContainer = Color(0xFFFFC9C2)
)

private val Light = lightColorScheme(
    primary = Ink.BlueDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0A2A5E),
    secondary = Color(0xFF8A6100),
    secondaryContainer = Color(0xFFEFF3F9),
    onSecondaryContainer = Color(0xFF16202E),
    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF141920),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF141920),
    surfaceVariant = Color(0xFFF1F4F9),
    onSurfaceVariant = Color(0xFF5C6675),
    outline = Color(0xFFD9E0EA),
    outlineVariant = Color(0xFFE7ECF3),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFDE7E5),
    onErrorContainer = Color(0xFF6B1810)
)

/** Tighter, heavier headings than the default; the body text stays as it is. */
private val Type = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        ),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    )
}

@Composable
fun TaplexTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Type,
        content = content
    )
}
