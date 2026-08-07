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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    Box(modifier) {
        // The frame is the supplied artwork. Its interior is transparent, so no
        // panel sits behind the card — that faint slab was the "sfondo dietro".
        // It is also downscaled to phone size: blitting a 1452px bitmap into a
        // small card is what made the blocks go soft mid-fling.
        Image(
            painter = painterResource(R.drawable.bg_card),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
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
