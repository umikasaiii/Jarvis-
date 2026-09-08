package com.simone.jarvismobile.ui.theme

import com.simone.jarvismobile.core.segnale.SegnaleQualityProfile

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §Y (Diagnostics).
 * Bounded, static presentation metadata — never message/agenda/health
 * contents, coordinates, memory text, tool arguments or secrets (§T).
 */
object SegnaleFoundation {
    /** Bumped only on a deliberate foundation change, not per-screen migration. */
    const val VERSION = "P0-1"

    /** §L — the declared HONOR 200 baseline. */
    val BASELINE_PROFILE: SegnaleQualityProfile = SegnaleQualityProfile.STANDARD

    /** §E — honest, not faked: no Inter/Space Grotesk font files are bundled yet (see `SegnaleTypography.kt`'s own doc comment). */
    const val FONT_STATUS = "fallback (system, gate pending)"
}
