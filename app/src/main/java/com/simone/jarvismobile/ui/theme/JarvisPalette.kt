package com.simone.jarvismobile.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A JARVIS visual theme (§ Impostazioni › Temi). Only the signature HUD hue
 * changes with the theme — success green, warning amber, the violet/rose
 * semantic accents and the neutral ink/muted text stay fixed everywhere, so a
 * theme switch never breaks what a colour means, only the brand accent itself.
 */
enum class JarvisThemeId(val storageId: String, val label: String) {
    BLU("blu", "Predefinito (Blu)"),
    ROSSO("rosso", "Rosso"),
    ;

    companion object {
        /** Unrecognised/empty ids fall back to the default rather than failing. */
        fun from(storageId: String): JarvisThemeId = entries.firstOrNull { it.storageId == storageId } ?: BLU
    }
}

/** The accent colours a theme actually changes. */
data class JarvisPalette(
    /** The primary HUD hue: borders, glows, the wordmark, active icons/text. */
    val accent: Color,
    /** A brighter variant used for emphasis (shadows, the active tab, "listening"). */
    val accentBright: Color,
)

private val BluPalette = JarvisPalette(
    accent = Color(0xFF3FD8F0),
    accentBright = Color(0xFF12D9FF),
)

// Placeholder values: the user is sending reference images for the red theme in
// a follow-up message, at which point these get refined to match them exactly.
// Reuses "Rose", the one red already in the app's own palette (JARVIS Drive's
// signature accent), so it is at least consistent with something that already
// exists rather than invented from nothing.
private val RossoPalette = JarvisPalette(
    accent = Color(0xFFFF5E5E),
    accentBright = Color(0xFFFF3B30),
)

fun paletteFor(id: JarvisThemeId): JarvisPalette = when (id) {
    JarvisThemeId.BLU -> BluPalette
    JarvisThemeId.ROSSO -> RossoPalette
}

/** Bound at the app root ([JarvisTheme]); every HUD-styled screen reads from here. */
val LocalJarvisPalette = staticCompositionLocalOf { BluPalette }

/**
 * Whether the current palette is the untouched default. Screens that recolour a
 * pre-rendered asset (a tinted glow baked into a PNG) use this to skip the tint
 * entirely on the default theme, so nothing about the existing, verified look
 * changes for a user who never opens Impostazioni › Temi.
 */
val LocalJarvisThemeId = staticCompositionLocalOf { JarvisThemeId.BLU }
