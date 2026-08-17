package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.core.driving.ManeuverUiModel
import com.simone.jarvismobile.ui.navigation.formatDistance
import com.simone.jarvismobile.ui.navigation.maneuverGlyph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Top-left turn card (spec §10 "MANEUVER CARD"). The caller decides whether
 * to show it at all — with no active navigation there is no maneuver, and
 * this composable is simply not placed, never rendered empty.
 */
@Composable
fun ManeuverCard(maneuver: ManeuverUiModel, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(JarvisDriveShapes.Card)
            .background(DrivingSportColors.PanelStrong)
            .border(1.5.dp, JarvisDriveBrushes.EdgeSoft, JarvisDriveShapes.Card)
            .padding(JarvisDriveDimensions.CardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(maneuverGlyph(maneuver.type), color = DrivingSportColors.TextMain, style = JarvisDriveTypography.CardValueLarge)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(formatDistance(maneuver.distanceMeters.toDouble()), color = DrivingSportColors.TextMain, style = JarvisDriveTypography.CardValueLarge)
            if (maneuver.roadName.isNotBlank()) {
                Text(maneuver.roadName, color = DrivingSportColors.Muted, style = JarvisDriveTypography.Body)
            }
        }
    }
}

/**
 * Top-right speed card (spec §10 "SPEED CARD"). [speedLimitKmh] only renders
 * when a real source provides one — never a guess.
 */
@Composable
fun SpeedCard(currentSpeedKmh: Int?, speedLimitKmh: Int?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(JarvisDriveShapes.Card)
            .background(DrivingSportColors.PanelStrong)
            .border(1.5.dp, JarvisDriveBrushes.EdgeSoft, JarvisDriveShapes.Card)
            .padding(JarvisDriveDimensions.CardPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (speedLimitKmh != null) {
            Text("LIMITE", color = DrivingSportColors.Muted, style = JarvisDriveTypography.CardLabel)
            Text("$speedLimitKmh", color = DrivingSportColors.TextMain, style = JarvisDriveTypography.CardValueMedium)
        }
        Text(
            currentSpeedKmh?.toString() ?: "—",
            color = DrivingSportColors.Accent,
            style = JarvisDriveTypography.CardValueLarge,
        )
        Text("KM/H", color = DrivingSportColors.Muted, style = JarvisDriveTypography.CardLabel)
    }
}

/**
 * Horizontal ETA bar (spec §10 "ETA BAR") — arrival clock time, remaining
 * minutes, remaining distance. The caller only places this while a
 * navigation is active; it disappears with it, never showing stale numbers.
 */
@Composable
fun EtaBar(
    etaEpochMs: Long?,
    remainingMinutes: Int?,
    remainingDistanceMeters: Int?,
    modifier: Modifier = Modifier,
    onStop: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(JarvisDriveShapes.Card)
            .background(DrivingSportColors.PanelStrong)
            .border(1.5.dp, JarvisDriveBrushes.EdgeSoft, JarvisDriveShapes.Card)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            EtaField(etaEpochMs?.let(::formatClock) ?: "—", "ARRIVO")
            EtaField(remainingMinutes?.toString() ?: "—", "MIN")
            EtaField(remainingDistanceMeters?.let { formatDistance(it.toDouble()) } ?: "—", "DISTANZA")
        }
        if (onStop != null) {
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(DrivingSportColors.Panel),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Termina navigazione", tint = DrivingSportColors.Accent)
            }
        }
    }
}

@Composable
private fun EtaField(value: String, label: String) {
    Box(Modifier.padding(horizontal = 10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = DrivingSportColors.TextMain, style = JarvisDriveTypography.CardValueMedium, textAlign = TextAlign.Center)
            Text(label, color = DrivingSportColors.Muted, style = JarvisDriveTypography.CardLabel)
        }
    }
}

private fun formatClock(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
