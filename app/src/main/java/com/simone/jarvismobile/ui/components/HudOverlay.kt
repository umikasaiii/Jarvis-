package com.simone.jarvismobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.simone.jarvismobile.R
import com.simone.jarvismobile.ui.theme.JarvisThemeId
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import com.simone.jarvismobile.ui.theme.LocalJarvisThemeId

/**
 * The futuristic frame: four transparent strips that sit ABOVE the fixed
 * background and BELOW the content, and never scroll.
 *
 * They are separate images on purpose — baked into the background they could
 * only ever be static wallpaper. Split out, each edge can breathe on its own
 * timing, which is what makes the screen feel like an instrument that is
 * powered on rather than a picture of one.
 *
 * Purely decorative, so the whole layer is hidden from accessibility.
 */
@Composable
fun HudOverlay(modifier: Modifier = Modifier, active: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "hud")

    @Composable
    fun pulse(periodMs: Int, low: Float, high: Float) = transition.animateFloat(
        initialValue = if (active) low else low,
        targetValue = if (active) high else low,
        animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
        label = "hudPulse",
    )

    // Slightly different periods so the edges never blink in unison — in sync
    // they read as a single flashing rectangle, out of sync as live circuitry.
    val top by pulse(3400, 0.55f, 0.95f)
    val sides by pulse(4700, 0.40f, 0.80f)
    val bottom by pulse(5300, 0.50f, 0.90f)

    // Same conditional recolour as JarvisCard's frame: untouched on the default
    // theme, tinted to the theme's accent otherwise. Computed once here rather
    // than inside each Edge() call.
    val themeId = LocalJarvisThemeId.current
    val tint = if (themeId == JarvisThemeId.BLU) null else ColorFilter.tint(LocalJarvisPalette.current.accent)

    Box(modifier.fillMaxSize()) {
        Edge(R.drawable.hud_top, Alignment.TopCenter, top, tint, Modifier.fillMaxWidth())
        Edge(R.drawable.hud_left, Alignment.TopStart, sides, tint)
        Edge(R.drawable.hud_right, Alignment.TopEnd, sides, tint)
        Edge(R.drawable.hud_bottom, Alignment.BottomCenter, bottom, tint, Modifier.fillMaxWidth())
    }
}

@Composable
private fun BoxScope.Edge(
    res: Int,
    alignment: Alignment,
    alpha: Float,
    tint: ColorFilter?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        colorFilter = tint,
        modifier = modifier
            .align(alignment)
            .alpha(alpha),
    )
}
