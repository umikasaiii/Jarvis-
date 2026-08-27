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
private val JarvisDeep = Color(0xFF0B1116)
private val JarvisSurface = Color(0xFF121A21)

@Composable
fun JarvisTheme(
    // JARVIS is a dark-only HUD: the whole UI (Scaffold, dashboard, orb) is
    // painted on a near-black background with hardcoded light-on-dark colours.
    // Following the system light theme only broke the screens that rely on
    // Material defaults (Impostazioni, Backup): black text on the app's dark
    // background, invisible. So the theme is always dark, whatever the phone is
    // set to — onSurface/onBackground stay light everywhere.
    darkTheme: Boolean = true,
    // The visual theme (§ Impostazioni › Temi). Only the accent hue changes:
    // Material's `primary` (buttons, switches, selected chips on every screen
    // that uses default Material styling — Impostazioni, Backup, Regole
    // avanzate) follows it here, and the HUD-styled screens (Dashboard, Agenda,
    // Chat, …) read the same palette via [LocalJarvisPalette].
    themeId: JarvisThemeId = JarvisThemeId.BLU,
    content: @Composable () -> Unit,
) {
    val palette = paletteFor(themeId)
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color(0xFF06222A),
            background = JarvisDeep,
            surface = JarvisSurface,
            onBackground = Color(0xFFE4EAEE),
            onSurface = Color(0xFFE4EAEE),
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            background = Color(0xFFF7FAFB),
            surface = Color(0xFFFFFFFF),
        )
    }
    MaterialTheme(colorScheme = scheme) {
        // MaterialTheme sets the palette but NOT the default text/icon colour —
        // that comes from LocalContentColor, which defaults to black and is only
        // overridden inside a Surface/Card. So top-level Text (e.g. the section
        // titles in Impostazioni) rendered black on the dark background. Provide
        // the light colour as the app-wide default; Cards and explicit colours
        // still override it locally.
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            LocalJarvisPalette provides palette,
            LocalJarvisThemeId provides themeId,
        ) {
            content()
        }
    }
}
