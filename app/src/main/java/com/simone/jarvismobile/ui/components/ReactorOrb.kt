package com.simone.jarvismobile.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * An original, JARVIS-inspired "reactor" orb: concentric rings, a slowly
 * rotating tick ring, a soft radial glow, and a pulsing core. It animates more
 * strongly while [active] (listening/speaking) and reacts to the mic [level].
 * The whole orb is the push-to-talk control.
 */
@Composable
fun ReactorOrb(
    accent: Color,
    active: Boolean,
    listening: Boolean,
    level: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (active) 6000 else 16000, easing = LinearEasing)),
        label = "rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            tween(1500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val coreScale = (if (active) pulse else 1f) + if (listening) level * 0.18f else 0f

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f

            // Soft outer glow.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
            // Concentric guide rings.
            drawCircle(accent.copy(alpha = 0.45f), radius = r * 0.90f, center = c, style = Stroke(width = r * 0.014f))
            drawCircle(accent.copy(alpha = 0.20f), radius = r * 0.72f, center = c, style = Stroke(width = r * 0.010f))

            // Rotating tick ring.
            rotate(degrees = rotation, pivot = c) {
                val ticks = 48
                for (i in 0 until ticks) {
                    val a = (2.0 * Math.PI * i / ticks).toFloat()
                    val inner = r * 0.80f
                    val outer = r * 0.90f
                    val start = Offset(c.x + cos(a) * inner, c.y + sin(a) * inner)
                    val end = Offset(c.x + cos(a) * outer, c.y + sin(a) * outer)
                    drawLine(
                        color = accent.copy(alpha = if (i % 4 == 0) 0.85f else 0.30f),
                        start = start,
                        end = end,
                        strokeWidth = r * 0.006f,
                    )
                }
            }
        }

        // Pulsing core disc (behind the glyph).
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .scale(0.55f * coreScale),
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent, accent.copy(alpha = 0.65f)),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
            )
        }

        // Constant-size glyph overlay.
        Icon(
            imageVector = if (active) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (active) "Interrompi" else "Parla",
            tint = Color.White,
            modifier = Modifier.size(64.dp),
        )
    }
}
