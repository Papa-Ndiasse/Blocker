package com.blocker.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0B1220)
val SurfaceDark = Color(0xFF131D31)
val SurfaceVariantDark = Color(0xFF1B2942)
val EmeraldPrimary = Color(0xFF10B981)
val EmeraldContainer = Color(0xFF064E3B)
val AmberWarning = Color(0xFFF59E0B)
val AmberContainer = Color(0xFF451A03)
val CrimsonAlert = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)

private val BlockerDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color(0xFF022C22),
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    error = CrimsonAlert,
    onError = Color.White
)

@Composable
fun BlockerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlockerDarkColorScheme,
        content = content
    )
}
