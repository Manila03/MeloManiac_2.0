package com.melomaniac.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE11D2E)
val AccentSoft = Color(0x33E11D2E)
val Background = Color(0xFF0D0D0F)
val Surface = Color(0xFF1C1C22)
val SurfaceElevated = Color(0xFF16161A)
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA0A0AB)
val TextMuted = Color(0xFF6B6B76)
val Border = Color(0xFF2E2E38)
val Track = Color(0xFF3A3A45)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    secondary = Accent,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    error = Color(0xFFEF4444),
)

@Composable
fun MeloTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
