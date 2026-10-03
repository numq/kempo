package io.github.numq.kempo.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object StudioColors {
    val Background = Color(0xFF0D0F14)
    val Surface = Color(0xFF141821)
    val SurfaceElevated = Color(0xFF1C2230)
    val SurfaceBorder = Color(0xFF2B3347)

    val PrimaryCyan = Color(0xFF00E5FF)
    val AccentAmber = Color(0xFFFF9100)
    val AccentPurple = Color(0xFFD500F9)
    val AccentGreen = Color(0xFF00E676)

    val TextPrimary = Color(0xFFF0F4FC)
    val TextSecondary = Color(0xFF8B98B5)
    val TextMuted = Color(0xFF505C77)

    val WaveformInactive = Color(0xFF262D3D)
    val CursorGlow = Color(0x6600E5FF)
}

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        background = StudioColors.Background,
        surface = StudioColors.Surface,
        surfaceVariant = StudioColors.SurfaceElevated,
        primary = StudioColors.PrimaryCyan,
        secondary = StudioColors.AccentAmber,
        tertiary = StudioColors.AccentPurple,
        onBackground = StudioColors.TextPrimary,
        onSurface = StudioColors.TextPrimary,
        onSurfaceVariant = StudioColors.TextSecondary,
        outline = StudioColors.SurfaceBorder
    )

    MaterialTheme(
        colorScheme = colorScheme, content = content
    )
}