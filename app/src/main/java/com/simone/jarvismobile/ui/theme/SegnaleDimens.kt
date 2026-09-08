package com.simone.jarvismobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §D. Centralized
 * spacing scale, component dimensions, corner radii, stroke widths, icon
 * sizing, elevation/surface levels and touch target sizes — the "do not
 * scatter constants across screens" requirement.
 *
 * Follows the same shape as the existing scoped token-object precedent,
 * [com.simone.jarvismobile.ui.driving.JarvisDriveDimensions] — a plain
 * `object` of `val`s, not a new settings/state owner.
 *
 * §D "Do not blindly resize every existing control in this pass" —
 * these are additive; no existing composable's dimensions were changed to
 * match them.
 */
object SegnaleSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object SegnaleRadii {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val pill = 999.dp
}

object SegnaleShapes {
    val surfaceSmall = RoundedCornerShape(SegnaleRadii.sm)
    val surfaceMedium = RoundedCornerShape(SegnaleRadii.md)
    val surfaceLarge = RoundedCornerShape(SegnaleRadii.lg)
    val pill = RoundedCornerShape(SegnaleRadii.pill)
}

object SegnaleStroke {
    val hairline = 1.dp
    val control = 1.5.dp
    val active = 2.dp
}

object SegnaleIconSize {
    val sm = 16.dp
    val md = 20.dp
    val lg = 24.dp
    val xl = 32.dp
}

/**
 * §D — surface/elevation levels as z-order hints, not literal Compose
 * `tonalElevation`/`shadowElevation` (SEGNALE's dark palette expresses depth
 * through [SegnaleColors.surface]/[SegnaleColors.elevated] flat fills and
 * border treatment, not Material shadow — §C "dark, precise surfaces").
 */
enum class SegnaleElevationLevel { BASE, RAISED, OVERLAY }

/**
 * §D — minimum interaction targets, exact values from the spec. §D "Do not
 * blindly resize every existing control in this pass" — these constants
 * exist for new/migrated SEGNALE components to consume, not to be applied
 * retroactively.
 */
object SegnaleTouchTarget {
    /** Standard interactive target. */
    val standard: Dp = 48.dp

    /** Primary action target. */
    val primary: Dp = 56.dp

    /** Driving interaction target. */
    val driving: Dp = 64.dp
}
