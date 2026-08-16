package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.core.driving.DrivingVoiceState

/**
 * The thin top bar (spec §10 "TOP BAR") — logo/name, a live wake-word/voice
 * status pill, and minimal icons. Never wider than one row so the map keeps
 * almost the entire screen.
 */
@Composable
fun JarvisDriveTopBar(
    voiceState: DrivingVoiceState,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(JarvisDriveDimensions.TopBarHeight)
            .background(DrivingSportColors.PanelStrong)
            .border(JarvisDriveDimensions.HairlineWidth, DrivingSportColors.Line)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(DrivingSportColors.Bg)
                    .border(JarvisDriveDimensions.HairlineWidth, DrivingSportColors.Accent, CircleShape),
            )
            Text(
                "JARVIS Drive",
                color = DrivingSportColors.TextMain,
                style = JarvisDriveTypography.CardValueMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
            if (voiceState == DrivingVoiceState.LISTENING) {
                Text(
                    "· In ascolto",
                    color = DrivingSportColors.Accent,
                    style = JarvisDriveTypography.CardTitle,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Row {
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = DrivingSportColors.Muted)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Impostazioni", tint = DrivingSportColors.Muted)
            }
        }
    }
}
