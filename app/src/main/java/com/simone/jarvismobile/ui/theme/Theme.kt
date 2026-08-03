package com.simone.jarvismobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Discreet, tech-inspired dark palette (docs/ARCHITECTURE.md §22). Deliberately
// original — no imitation of any protected cinematic interface.
private val JarvisCyan = Color(0xFF4FD1E0)
private val JarvisDeep = Color(0xFF0B1116)
private val JarvisSurface = Color(0xFF121A21)

private val DarkColors = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = Color(0xFF06222A),
    background = JarvisDeep,
    surface = JarvisSurface,
    onBackground = Color(0xFFE4EAEE),
    onSurface = Color(0xFFE4EAEE),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F7C8C),
    background = Color(0xFFF7FAFB),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
