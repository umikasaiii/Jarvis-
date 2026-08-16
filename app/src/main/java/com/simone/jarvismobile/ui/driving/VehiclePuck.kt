package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The vehicle marker for the internal navigation map (spec §9/§10) — a JARVIS
 * resource, never the stock Google blue dot. Deliberately its own small,
 * swappable composable rather than inlined where it's placed, so a real bitmap/
 * vector asset can replace [Icons.Filled.Navigation] later with a one-file
 * change. [bearingDegrees] rotates it to the direction of travel when the map
 * itself is not bearing-rotated yet (JarvisMapView's camera is north-up today).
 */
@Composable
fun VehiclePuck(bearingDegrees: Float? = null, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(JarvisDriveDimensions.PuckSize)
            .clip(CircleShape)
            .background(DrivingSportColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Navigation,
            contentDescription = "Posizione veicolo",
            tint = DrivingSportColors.Accent,
            modifier = Modifier.size(JarvisDriveDimensions.PuckSize - 8.dp)
                .graphicsLayer { rotationZ = bearingDegrees ?: 0f },
        )
    }
}
