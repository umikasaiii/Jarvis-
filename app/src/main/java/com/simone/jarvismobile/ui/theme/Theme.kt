package com.simone.jarvismobile.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    // JARVIS is a dark-only HUD: the whole UI (Scaffold, dashboard, orb) is
    // painted on a near-black background with hardcoded light-on-dark colours.
    // Following the system light theme only broke the screens that rely on
    // Material defaults (Impostazioni, Backup): black text on the app's dark
    // background, invisible. So the theme is always dark, whatever the phone is
    // set to — onSurface/onBackground stay light everywhere.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = scheme) {
        // MaterialTheme sets the palette but NOT the default text/icon colour —
        // that comes from LocalContentColor, which defaults to black and is only
        // overridden inside a Surface/Card. So top-level Text (e.g. the section
        // titles in Impostazioni) rendered black on the dark background. Provide
        // the light colour as the app-wide default; Cards and explicit colours
        // still override it locally.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            content()
        }
    }
}
