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
    /** The original look, unchanged. Storage id kept as "blu" for compatibility
     *  with whatever a user already had saved before this option existed. */
    BLU("blu", "Classico"),
    /** Every HUD surface recoloured to red via [androidx.compose.ui.graphics.ColorFilter.tint]. */
    ROSSO("rosso", "Rosso"),
    /** Like [ROSSO], but the card frame, orb and dashboard background are real
     *  art from the user's own reference images instead of a tinted recolour —
     *  see [com.simone.jarvismobile.ui.components.JarvisCard], [com.simone.jarvismobile.ui.components.JarvisOrb]. */
    ROUGE("rouge", "Rouge"),
    /** A fourth theme with its own Home *layout*, not just its own colours/art
     *  (§ richiesta esplicita dell'utente: "cambiamo anche le impostazioni dei
     *  blocchi, cambiano solo quelli di questo tema senza toccare gli altri") —
     *  see [com.simone.jarvismobile.ui.dashboard.AresHomeScreen]. Shares the
     *  same measured red accent as Rosso/Rouge (same reference art family).
     *  Renamed "Atena" in the visible label (§ richiesta esplicita dell'utente,
     *  stesso giorno: "invece di Ares si chiamerà 'Atena'") — storageId kept
     *  as "ares" for compatibility with a device that already selected it
     *  (this session's own test device), same pattern as BLU's stable
     *  "blu" storageId under the "Classico" label. Kotlin identifiers
     *  (this enum entry, AresHomeScreen/AresViewModel/Ares* composables)
     *  are internal code names, not user-visible, so left unrenamed. */
    ARES("ares", "Atena"),
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

// Measured, not eyeballed: sampled directly from the 32 reference PNGs the user
// sent (Pillow, pixel-by-pixel over every asset) rather than reusing a value
// that was already in the app. Two passes — the glow line itself (excluding
// both the near-white specular "hot core" bloom and the darker background
// texture) averaged to #DF241C across ~225k sampled pixels; the most saturated,
// brightest pure-red pixels (R>225, G/B<90) averaged to #F52A1C across ~139k.
// accent below is that line colour nudged slightly brighter for legibility as
// small text/icons against the app's near-black background (the source art sits
// on mid-dark panels, not near-black); accentBright is the vivid sample as-is.
private val RossoPalette = JarvisPalette(
    accent = Color(0xFFE8362B),
    accentBright = Color(0xFFF52A1C),
)

fun paletteFor(id: JarvisThemeId): JarvisPalette = when (id) {
    JarvisThemeId.BLU -> BluPalette
    // All three red themes share the exact same measured accent — it was
    // sampled from this same reference pack in the first place, so there is
    // no second colour to invent; only the chrome art (and, for Ares, the
    // whole Home layout) differs.
    JarvisThemeId.ROSSO, JarvisThemeId.ROUGE, JarvisThemeId.ARES -> RossoPalette
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
