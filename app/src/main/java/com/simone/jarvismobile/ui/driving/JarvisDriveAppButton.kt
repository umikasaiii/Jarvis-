package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Reference "09_APP_DRAWER_BUTTON" — same circular chrome as [LayersButton],
 * mirrored on the opposite side of the voice dock
 * ([com.simone.jarvismobile.core.driving.golden.JarvisDriveGoldenLayout.AppButtonCenter]).
 *
 * Not placed in [DrivingModeScreen] yet, deliberately: an earlier pass asked
 * what this button should actually open and never got an answer, and this
 * project doesn't wire real-looking buttons to no-op actions. The composable
 * exists — modular, positionable, styled — so placing it is a one-line change
 * once there's a real destination for [onClick].
 */
@Composable
fun JarvisDriveAppButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .clip(JarvisDriveShapes.Dock)
            .background(DrivingSportColors.PanelStrong)
            .border(1.5.dp, JarvisDriveBrushes.Edge, JarvisDriveShapes.Dock),
    ) {
        Icon(Icons.Filled.Apps, contentDescription = "App", tint = DrivingSportColors.TextMain)
    }
}
