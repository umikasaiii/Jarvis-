package com.simone.jarvismobile.ui.components

import com.simone.jarvismobile.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.ui.theme.JarvisThemeId
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import com.simone.jarvismobile.ui.theme.LocalJarvisThemeId

/**
 * A dashboard card: the supplied HUD frame with the content laid over it.
 *
 * The artwork is pre-processed rather than used raw. Its interior is made fully
 * transparent, so nothing sits behind the card — the faint slab that used to
 * show through was the artwork's own dark fill, not a background — and it is
 * downscaled to phone size, because blitting a 1452px bitmap into a small card
 * is what made the blocks go soft during a fling and sharpen on release.
 */
@Composable
fun JarvisCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 18.dp,
    badge: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The artwork is a near-monochrome blue glow (hue barely varies; the shape
    // comes from alpha). On the default theme it is left completely untouched —
    // pixel-identical to before this existed. On Rosso it is recoloured with a
    // straight SrcIn tint: since the source is already essentially a coloured
    // mask, replacing its hue while keeping its alpha reproduces the same glow
    // shape in the new accent, no new art needed. Rouge instead swaps in real
    // reference art the user provided (a genuine panel, not a tinted approximation
    // of the blue one) — it needs no tint, since it is already red.
    val themeId = LocalJarvisThemeId.current
    val cardRes = if (themeId == JarvisThemeId.ROUGE) R.drawable.rouge_bg_card else R.drawable.bg_card
    // Ares has no dedicated card-frame art of its own (most of its blocks use
    // their own bespoke background images instead — see AresHomeScreen), so
    // for the handful of cards it does reuse (Sistema, Domotica) it falls
    // back to the same tinted default frame Rosso already uses, rather than
    // rendering blue on a red theme.
    val tint = if (themeId == JarvisThemeId.ROSSO || themeId == JarvisThemeId.ARES) {
        ColorFilter.tint(LocalJarvisPalette.current.accent)
    } else {
        null
    }
    Box(modifier) {
        // The frame is the supplied artwork. Its interior is transparent, so no
        // panel sits behind the card — that faint slab was the "sfondo dietro".
        // It is also downscaled to phone size: blitting a 1452px bitmap into a
        // small card is what made the blocks go soft mid-fling.
        Image(
            painter = painterResource(cardRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = tint,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            content = content,
        )
        if (badge != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 14.dp)) { badge() }
        }
    }
}
