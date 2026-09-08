package com.simone.jarvismobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 (SEGNALE P0 —
 * Design Token Foundation, §D). The one authoritative SEGNALE color token
 * set — exact values as specified, not eyeballed/re-derived from anything
 * already in [JarvisPalette].
 *
 * Deliberately does NOT replace [JarvisPalette]/[JarvisTheme]: those remain
 * the live color source for every existing screen (Blu/Rosso/Rouge/Atena),
 * unchanged and unmigrated by this pass (§U — no whole-app conversion).
 * [SegnaleColors] is the new authoritative set for SEGNALE components going
 * forward (`ui/components/segnale/`) and for the one proof migration this
 * pass performs — the two coexist deliberately during the gradual migration
 * this pass is required to allow.
 *
 * RED is localized energy/active state — §C/§D: do not reach for
 * [borderActive]/[energyRed] for an arbitrary category or every border.
 * [error]/[warning]/[success]/[remote] are the semantic tokens for their own
 * meanings; they are not substitutes for [energyRed].
 */
object SegnaleColors {
    val background = Color(0xFF070A0E)
    val surface = Color(0xFF10161C)
    val elevated = Color(0xFF192129)

    val borderInactive = Color(0xFF36414B)
    val borderControl = Color(0xFF697884)
    val borderActive = Color(0xFFF04452)

    val redDeep = Color(0xFFB9273A)
    val energyRed = Color(0xFFFF4B55)
    val ambientLight = Color(0xFF5A1622)

    val warning = Color(0xFFEAB866)
    val success = Color(0xFF78CCAD)
    val error = Color(0xFFFF9D93)
    val remote = Color(0xFF88BFE7)

    val textPrimary = Color(0xFFF3F5F7)
    val textSecondary = Color(0xFFACB7C2)
    val textMuted = Color(0xFF8995A2)

    /** The color to draw content ON TOP OF an energy-accented surface (§D: "onEnergy"). */
    val onEnergy = Color(0xFF070A0E)
}
