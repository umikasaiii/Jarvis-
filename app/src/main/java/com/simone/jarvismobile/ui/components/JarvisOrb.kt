package com.simone.jarvismobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val glowLow: Float,
    val glowHigh: Float,
    /** Breathing period. Shorter = more urgent. */
    val breathMs: Int,
)

private fun lookFor(state: OrbState): OrbLook = when (state) {
    OrbState.IDLE -> OrbLook(Color(0xFF12D9FF), 0.28f, 0.55f, 2600)
    OrbState.LISTENING -> OrbLook(Color(0xFF12D9FF), 0.60f, 1.00f, 900)
    OrbState.THINKING -> OrbLook(Color(0xFF2DAEFF), 0.45f, 0.85f, 1300)
    OrbState.SPEAKING -> OrbLook(Color(0xFF7FE9FF), 0.50f, 0.90f, 1100)
    OrbState.ERROR -> OrbLook(Color(0xFFFF6B5B), 0.25f, 0.60f, 2200)
}

/**
 * The listen control: the orb artwork, a breathing glow behind it, and an
 * elastic press.
 *
 * Nothing is drawn ON the artwork. Earlier versions added rotating rings and a
 * tap wave in code, but the image already has concentric rings of its own, so
 * the drawn ones landed at a different angle and read as crooked. The glow
 * still derives its rhythm from [state], so the orb keeps telling the truth
 * about what JARVIS is doing without competing with its own artwork.
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

        // --- breathing glow ------------------------------------------------
        // The artwork already contains its own concentric rings. Drawing more
        // rings in code put a second, differently-angled set on top of them,
        // which is what read as crooked. Only the glow is animated now.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(look.accent.copy(alpha = glow * 0.40f), Color.Transparent),
                    center = center,
                    radius = this.size.minDimension / 2f,
                ),
                radius = this.size.minDimension / 2f,
            )
        }

        // --- the artwork itself -------------------------------------------
        Image(
            painter = painterResource(R.drawable.orb),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .scale(press.value)
                .clickableNoRipple(interaction, onClick),
        )
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
