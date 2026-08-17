package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.core.driving.ManeuverUiModel
import com.simone.jarvismobile.ui.navigation.formatDistance
import com.simone.jarvismobile.ui.navigation.maneuverGlyph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Top-left turn card (reference "03_TURN_MANEUVER_CARD"). Drawn over the real
 * reference frame asset (`drive_hud_maneuver`, an icon well on the left plus
 * a content well on the right) instead of a Compose-redrawn panel — Compose
 * only places the dynamic glyph/distance/road name on top of it. The caller
 * decides whether to show it at all — with no active navigation there is no
 * maneuver, and this composable is simply not placed, never rendered empty.
 */
@Composable
fun ManeuverCard(maneuver: ManeuverUiModel, modifier: Modifier = Modifier) {
    Box(modifier.width(230.dp)) {
        Image(
            painter = painterResource(JarvisDriveAssets.ManeuverCard),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(58.dp), contentAlignment = Alignment.Center) {
                Text(maneuverGlyph(maneuver.type), color = DrivingSportColors.TextMain, style = JarvisDriveTypography.CardValueLarge)
            }
            Spacer(Modifier.size(8.dp))
            Column {
                Text(formatDistance(maneuver.distanceMeters.toDouble()), color = DrivingSportColors.TextMain, style = JarvisDriveTypography.CardValueLarge)
                if (maneuver.roadName.isNotBlank()) {
                    Text(maneuver.roadName, color = DrivingSportColors.Muted, style = JarvisDriveTypography.Body)
                }
            }
        }
    }
}

/**
 * Top-right speed card (reference "04_SPEED_LIMIT_CARD") over the real
 * `drive_hud_speed` frame asset, whose baked horizontal divider is what the
 * reference uses to separate the posted limit from the current reading —
 * [speedLimitKmh] only renders when a real source provides one, never a guess.
 */
@Composable
fun SpeedCard(currentSpeedKmh: Int?, speedLimitKmh: Int?, modifier: Modifier = Modifier) {
    Box(modifier.width(120.dp)) {
        Image(
            painter = painterResource(JarvisDriveAssets.SpeedCard),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (speedLimitKmh != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LIMITE", color = DrivingSportColors.Muted, style = JarvisDriveTypography.CardLabel)
                    Text("$speedLimitKmh", color = DrivingSportColors.TextMain, style = JarvisDriveTypography.CardValueMedium)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    currentSpeedKmh?.toString() ?: "—",
                    color = DrivingSportColors.Accent,
                    style = JarvisDriveTypography.CardValueLarge,
                )
                Text("KM/H", color = DrivingSportColors.Muted, style = JarvisDriveTypography.CardLabel)
            }
        }
    }
}

/**
 * Horizontal ETA bar (reference "05_ETA_BAR") — arrival clock time, remaining
 * minutes, remaining distance, over the real `drive_hud_eta` frame asset,
 * whose baked circular button on the right is where the stop-navigation
 * control sits. The caller only places this while a navigation is active; it
 * disappears with it, never showing stale numbers.
 */
@Composable
fun EtaBar(
    etaEpochMs: Long?,
    remainingMinutes: Int?,
    remainingDistanceMeters: Int?,
    modifier: Modifier = Modifier,
    onStop: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth()) {
        Image(
            painter = painterResource(JarvisDriveAssets.EtaBar),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                EtaField(etaEpochMs?.let(::formatClock) ?: "—", "ARRIVO")
                EtaField(remainingMinutes?.toString() ?: "—", "MIN")
                EtaField(remainingDistanceMeters?.let { formatDistance(it.toDouble()) } ?: "—", "DISTANZA")
            }
            if (onStop != null) {
                IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Termina navigazione", tint = DrivingSportColors.Accent)
                }
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
