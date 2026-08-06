package com.simone.jarvismobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.R

/**
 * What the orb is doing. It mirrors the real assistant state — the animation is
 * not decoration, it is the status indicator, so it must never show "listening"
 * when the microphone is closed.
 */
enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

private data class OrbLook(
    val accent: Color,
    /** Seconds for one full turn of the outer ring. Lower = more urgent. */
    val outerPeriod: Int,
    val innerPeriod: Int,
    val glowLow: Float,
    val glowHigh: Float,
    val breathMs: Int,
)

private fun lookFor(state: OrbState): OrbLook = when (state) {
    OrbState.IDLE -> OrbLook(Color(0xFF12D9FF), 14, 10, 0.28f, 0.55f, 2600)
    OrbState.LISTENING -> OrbLook(Color(0xFF12D9FF), 3, 2, 0.60f, 1.00f, 900)
    OrbState.THINKING -> OrbLook(Color(0xFF2DAEFF), 5, 4, 0.45f, 0.85f, 1300)
    OrbState.SPEAKING -> OrbLook(Color(0xFF7FE9FF), 8, 6, 0.50f, 0.90f, 1100)
    OrbState.ERROR -> OrbLook(Color(0xFFFF6B5B), 20, 16, 0.25f, 0.60f, 2200)
}

/**
 * The listen control, built as stacked layers rather than one animated picture:
 *
 * ```
 * glow · outer ring (turning) · orb artwork · inner ring (counter-turning)
 *      · central pulse · tap wave
 * ```
 *
 * Each layer reacts differently, which is what makes it feel alive. Everything
 * is drawn with [Canvas] and [Animatable] instead of a GIF or a Lottie file: it
 * stays at 60 FPS, weighs nothing, and — the point — every timing is derived
 * from [state], so the orb always tells the truth about what JARVIS is doing.
 */
@Composable
fun JarvisOrb(
    state: OrbState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 210.dp,
) {
    val look = lookFor(state)
    val transition = rememberInfiniteTransition(label = "orb")

    val outer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(look.outerPeriod * 1000, easing = LinearEasing),
        ),
        label = "outer",
    )
    val inner by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(look.innerPeriod * 1000, easing = LinearEasing),
        ),
        label = "inner",
    )
    val glow by transition.animateFloat(
        initialValue = look.glowLow,
        targetValue = look.glowHigh,
        animationSpec = infiniteRepeatable(
            tween(look.breathMs, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "glow",
    )

    // Press feedback: shrink, then overshoot back — the elastic return is what
    // reads as physical rather than as a colour change.
    val press = remember { Animatable(1f) }
    // Expanding ring emitted on each press.
    val wave = remember { Animatable(0f) }
    val waveAlpha = remember { Animatable(0f) }

    LaunchedEffect(state) {
        if (state == OrbState.LISTENING || state == OrbState.THINKING) {
            wave.snapTo(0f); waveAlpha.snapTo(0.9f)
            wave.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = when (state) {
                    OrbState.LISTENING -> "Interrompi l'ascolto"
                    OrbState.THINKING -> "Sto elaborando, tocca per annullare"
                    OrbState.SPEAKING -> "Sto parlando, tocca per interrompere"
                    else -> "Avvia l'ascolto"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val interaction = remember { MutableInteractionSource() }

        LaunchedEffect(interaction) {
            interaction.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        press.animateTo(0.94f, tween(90))
                    }
                    else -> {
                        press.animateTo(1.06f, tween(120))
                        press.animateTo(1f, tween(160))
                    }
                }
            }
        }

        // --- tap wave -----------------------------------------------------
        Canvas(Modifier.fillMaxSize()) {
            val a = (1f - wave.value) * waveAlpha.value
            if (a > 0.01f) {
                val r = this.size.minDimension / 2f * (0.68f + wave.value * 0.62f)
                drawCircle(
                    color = look.accent.copy(alpha = a * 0.55f),
                    radius = r,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // --- outer glow ---------------------------------------------------
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(look.accent.copy(alpha = glow * 0.35f), Color.Transparent),
                    center = center,
                    radius = this.size.minDimension / 2f,
                ),
                radius = this.size.minDimension / 2f,
            )
        }

        // --- outer ring, turning ------------------------------------------
        Canvas(
            Modifier
                .fillMaxSize()
                .rotate(outer),
        ) {
            val d = this.size.minDimension
            val inset = d * 0.03f
            val arc = Size(d - inset * 2, d - inset * 2)
            drawCircle(
                color = look.accent.copy(alpha = 0.22f),
                radius = d / 2f - inset,
                style = Stroke(width = 1.dp.toPx()),
            )
            // Three bright segments so the rotation is visible.
            listOf(-90f, 30f, 150f).forEach { start ->
                drawArc(
                    color = look.accent.copy(alpha = glow),
                    startAngle = start,
                    sweepAngle = 26f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arc,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        // --- the artwork itself -------------------------------------------
        Image(
            painter = painterResource(R.drawable.orb),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .scale(press.value * 0.86f)
                .clickableNoRipple(interaction, onClick),
        )

        // --- inner ring, counter-turning ----------------------------------
        Canvas(
            Modifier
                .fillMaxSize()
                .rotate(inner),
        ) {
            val d = this.size.minDimension * 0.58f
            val off = (this.size.minDimension - d) / 2f
            listOf(0f, 90f, 180f, 270f).forEach { start ->
                drawArc(
                    color = look.accent.copy(alpha = 0.55f * glow + 0.15f),
                    startAngle = start + 10f,
                    sweepAngle = 55f,
                    useCenter = false,
                    topLeft = Offset(off, off),
                    size = Size(d, d),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        // --- central pulse -------------------------------------------------
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension * (0.055f + 0.012f * glow)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.95f), look.accent.copy(alpha = 0f)),
                    center = center,
                    radius = r * 3f,
                ),
                radius = r * 3f,
            )
            drawCircle(color = Color.White.copy(alpha = 0.9f * glow), radius = r)
        }
    }
}

/**
 * Click with no Material ripple: the default indication draws a rectangular
 * highlight, which would visibly square off a perfectly round control.
 */
private fun Modifier.clickableNoRipple(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interaction,
    indication = null,
    onClick = onClick,
)
