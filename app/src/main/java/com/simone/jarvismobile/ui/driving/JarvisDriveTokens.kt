package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Non-color design tokens for JARVIS Drive (spec §18) — the reference-styled
 * components shared between the existing Google-Maps overlay and the new
 * internal `DrivingModeActivity`. Colors are deliberately **not** duplicated
 * here: [DrivingSportColors] is already the one palette both surfaces use
 * (see its own doc comment), so every new component in this package reads
 * from it directly instead of a second, parallel color object.
 */
object JarvisDriveDimensions {
    val CardCornerRadius = 14.dp
    val CardPadding = 14.dp
    val CardSpacing = 10.dp
    val HairlineWidth = 1.dp
    val TopBarHeight = 52.dp
    val VoiceDockHeight = 108.dp
    val VoiceDockMicSize = 64.dp
    val PuckSize = 34.dp
    val ScreenMargin = 12.dp
}

object JarvisDriveShapes {
    val Card = RoundedCornerShape(JarvisDriveDimensions.CardCornerRadius)
    val Pill = RoundedCornerShape(50)

    /**
     * The chamfered "technical panel" bar from the reference kit's voice
     * dock and side buttons — a fixed corner cut (not percent-based) so it
     * reads as a consistent bevel regardless of the bar's height, unlike
     * [RoundedCornerShape] which the old plain pill used.
     */
    val Dock = CutCornerShape(22.dp)
}

/**
 * The dual red→blue edge from the reference JARVIS Drive kit
 * (`jarvis_drive_reference` sprite sheet) — every card/panel border in that
 * kit reads as a horizontal gradient from red (left) to blue (right), never
 * a flat single-hue line. [Edge] mirrors the old [DrivingSportColors.LineStrong]
 * weight (emphasized panels: the voice dock, the mic ring), [EdgeSoft] the
 * old [DrivingSportColors.Line] weight (regular cards).
 */
object JarvisDriveBrushes {
    val Edge: Brush = Brush.horizontalGradient(listOf(DrivingSportColors.Accent, DrivingSportColors.AccentBlue))
    val EdgeSoft: Brush = Brush.horizontalGradient(
        listOf(DrivingSportColors.Accent.copy(alpha = 0.55f), DrivingSportColors.AccentBlue.copy(alpha = 0.55f)),
    )
}

object JarvisDriveTypography {
    val CardTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val CardValueLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
    val CardValueMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val CardLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 13.sp)
}
