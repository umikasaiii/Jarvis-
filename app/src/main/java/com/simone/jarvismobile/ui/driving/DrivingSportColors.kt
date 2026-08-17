package com.simone.jarvismobile.ui.driving

import androidx.compose.ui.graphics.Color

/**
 * Sport Mode palette, carried over from `jarvis_modalita_guida_v5_sport.html`
 * (spec §15) — a dark, near-black red/red-orange accent skin used only by the
 * driving overlay, deliberately separate from the app's own cyan HUD theme
 * ([com.simone.jarvismobile.ui.theme.JarvisTheme]) since the overlay is a
 * distinct floating surface, not a screen inside that theme's Scaffold.
 */
object DrivingSportColors {
    val Bg = Color(0xFF090304)
    val Panel = Color(0xC8120810)
    val PanelStrong = Color(0xEB14080A)
    val Line = Color(0x2EFF5E5E)
    val LineStrong = Color(0x57FF7C7C)
    val Accent = Color(0xFFFF5E5E)
    val Accent2 = Color(0xFFFF8747)
    val AccentSoft = Color(0x1AFF5E5E)
    val TextMain = Color(0xFFFFF6F4)
    val Muted = Color(0xFFCFB4B1)
    /**
     * Success/OK accent — deliberately red-orange, not green, so the Sport
     * palette never borrows the general app theme's green ([androidx.compose.material3]
     * default success color): the driving overlay's active-state language is
     * red/orange end to end, per the reference kit.
     */
    val Ok = Color(0xFFFF8747)

    /**
     * The dual red/blue neon accent from the reference JARVIS Drive kit
     * (`jarvis_drive_reference` sprite sheet) — used only as the second stop
     * of an edge-gradient border ([JarvisDriveBrushes]), never as a standalone
     * fill, so the rest of the palette (still red-only for state/meaning:
     * voice states, traffic colors, accents) is unaffected.
     */
    val AccentBlue = Color(0xFF3AC0FF)

    /**
     * READY was green ([0xFF7EF6A4]) — the one leak of the general app theme's
     * "ok = green" language into this overlay. The reference kit's "Pronto"/
     * "Listening" states are both red/orange (only intensity differs), so
     * READY is now a dim ember red rather than a different hue entirely.
     */
    val VoiceReady = Color(0xFFB2524A)
    val VoiceListening = Color(0xFFFF9C7A)
    val VoiceProcessing = Color(0xFFFFC06E)
    val VoiceReplying = Color(0xFFFF7373)
}
