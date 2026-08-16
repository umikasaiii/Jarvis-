package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.shape.RoundedCornerShape
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
    val Dock = RoundedCornerShape(28.dp)
}

object JarvisDriveTypography {
    val CardTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val CardValueLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
    val CardValueMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val CardLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 13.sp)
}
