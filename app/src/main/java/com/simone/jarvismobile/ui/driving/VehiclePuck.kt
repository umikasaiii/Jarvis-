package com.simone.jarvismobile.ui.driving

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The vehicle marker for the internal navigation map (spec §9/§10) — a JARVIS
 * resource, never the stock Google blue dot: a white/metal arrow on a dark
 * disc with a faint red halo, matching the reference's puck. Deliberately its
 * own small, swappable composable rather than inlined where it's placed, so a
 * real bitmap/vector asset can replace [Icons.Filled.Navigation] later with a
 * one-file change.
 *
 * [bearingDegrees] rotates it to the direction of travel, animated smoothly
 * rather than snapping — used when the map itself stays north-up (e.g. a
 * future non-follow rendering); JARVIS Drive's follow mode instead rotates
 * the *map* to heading-up and passes null here (see [DrivingModeScreen]).
 */
@Composable
fun VehiclePuck(bearingDegrees: Float? = null, modifier: Modifier = Modifier) {
    val animatedBearing by animateFloatAsState(
        targetValue = bearingDegrees ?: 0f,
        animationSpec = tween(300),
        label = "vehicle-puck-bearing",
    )
    Box(
        modifier
            .size(JarvisDriveDimensions.PuckSize + 14.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(DrivingSportColors.Accent.copy(alpha = 0.22f), DrivingSportColors.Accent.copy(alpha = 0f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(JarvisDriveDimensions.PuckSize)
                .clip(CircleShape)
                .background(DrivingSportColors.Bg)
                .border(1.dp, DrivingSportColors.LineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Navigation,
                contentDescription = "Posizione veicolo",
                tint = DrivingSportColors.TextMain,
                modifier = Modifier.size(JarvisDriveDimensions.PuckSize - 10.dp)
                    .graphicsLayer { rotationZ = animatedBearing },
            )
        }
    }
}
