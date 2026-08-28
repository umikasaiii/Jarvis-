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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.R
import com.simone.jarvismobile.ui.theme.JarvisThemeId
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import com.simone.jarvismobile.ui.theme.LocalJarvisThemeId
import kotlinx.coroutines.launch

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

/**
 * IDLE/LISTENING were always meant to track the brand accent (LISTENING's
 * original fixed value was exactly the old accentBright) — [accent]/
 * [accentBright] are the live theme colours, read once in the caller's
 * composable scope since this function itself is not @Composable.
 */
private fun lookFor(state: OrbState, accent: Color, accentBright: Color): OrbLook = when (state) {
    OrbState.IDLE -> OrbLook(accent, 0.22f, 0.48f, 3000)
    OrbState.LISTENING -> OrbLook(accentBright, 0.35f, 1.00f, 780)
    OrbState.THINKING -> OrbLook(Color(0xFF9B7BFF), 0.40f, 0.90f, 1100)
    OrbState.SPEAKING -> OrbLook(Color(0xFF4FE3C1), 0.45f, 0.92f, 1000)
    OrbState.ERROR -> OrbLook(Color(0xFFFF6B5B), 0.20f, 0.60f, 2200)
}

/**
 * The listen control: the orb artwork, a breathing glow behind it, and an
 * elastic press on touch.
 *
 * The artwork itself never moves. Scaling it made the whole image pump, which
 * looked wrong against its own fixed rings — the breath is light only. Its depth
 * and speed come from the state, so at rest the glow barely stirs and while
 * listening it swings the full range, quickly.
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
    val palette = LocalJarvisPalette.current
    val look = lookFor(state, palette.accent, palette.accentBright)
    // Rouge (§ Impostazioni › Temi) swaps in real reference art for the orb
    // itself instead of tinting the default blue/white one — the original has
    // a rich white-hot core/blue-rim gradient a flat SrcIn tint would flatten
    // into a single colour, so Rosso deliberately leaves it untouched; Rouge's
    // asset is real red-on-black art, no tint needed either way.
    val orbRes = if (LocalJarvisThemeId.current == JarvisThemeId.ROUGE) R.drawable.rouge_orb else R.drawable.orb
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

    // A tap shockwave (§ richiesta esplicita dell'utente: "dai un effetto
    // diverso e migliore se cliccato") — a soft ring that expands from the
    // centre and fades, drawn behind the artwork in the same Canvas as the
    // breathing glow. Never ON the image: the artwork already owns concentric
    // rings of its own (see the doc comment below), so this stays a burst of
    // light rather than a second ring competing at a different angle.
    val burst = remember { Animatable(0f) }

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
                        // A fresh shockwave on every tap — restarts from zero
                        // even if a previous one hasn't finished fading, so
                        // quick repeated taps each get their own visible ring.
                        launch {
                            burst.snapTo(0f)
                            burst.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                            burst.snapTo(0f)
                        }
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
            if (burst.value > 0f) {
                val maxRadius = this.size.minDimension / 2f
                drawCircle(
                    color = look.accent.copy(alpha = (1f - burst.value) * 0.55f),
                    radius = maxRadius * (0.35f + 0.65f * burst.value),
                    style = Stroke(width = 5.dp.toPx()),
                )
            }
        }

        // --- the artwork itself -------------------------------------------
        Image(
            painter = painterResource(orbRes),
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
