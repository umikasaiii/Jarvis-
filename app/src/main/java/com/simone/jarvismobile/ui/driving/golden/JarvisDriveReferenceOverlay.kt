package com.simone.jarvismobile.ui.driving.golden

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.simone.jarvismobile.R
import com.simone.jarvismobile.core.driving.golden.JarvisDriveGoldenLayout

/**
 * Debug-only golden-reference overlay (spec §3): draws
 * `jarvis_drive_golden_reference.png` (the reference composite cropped to
 * just its screen content, see [JarvisDriveGoldenLayout]'s doc comment for
 * the crop) on top of the real UI at a configurable opacity, so the two can
 * be compared pixel-by-pixel by eye instead of from memory.
 *
 * Never interactive — plain [Image]/[Box] carry no click or pointer-input
 * modifiers, so Compose's hit-testing passes every touch straight through to
 * whatever is underneath; the whole layer is also marked [invisibleToUser]
 * since it exists only to be looked at, not announced. Only ever composed
 * from a debug-gated call site (this file
 * carries no `BuildConfig.DEBUG` check itself — the caller decides whether
 * to place it at all, same pattern as [com.simone.jarvismobile.navigation.debug.DebugGpsSimulator]).
 *
 * The reference is letterboxed/pillarboxed via
 * [JarvisDriveGoldenLayout.fitReference], never stretched: a real device's
 * aspect ratio is rarely identical to the 845×1620 reference crop, and
 * stretching would silently invalidate every [com.simone.jarvismobile.core.driving.golden.GoldenRect]
 * measured from the reference at its own proportions.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun JarvisDriveReferenceOverlay(
    opacityPercent: Int,
    modifier: Modifier = Modifier,
) {
    if (opacityPercent <= 0) return
    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxSize()
            .semantics { invisibleToUser() },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val containerHeightPx = with(density) { maxHeight.toPx() }
            val fitted = JarvisDriveGoldenLayout.fitReference(containerWidthPx, containerHeightPx)
            val xDp: Dp = with(density) { fitted.x.toDp() }
            val yDp: Dp = with(density) { fitted.y.toDp() }
            val widthDp: Dp = with(density) { fitted.width.toDp() }
            val heightDp: Dp = with(density) { fitted.height.toDp() }
            val painter: Painter = painterResource(R.drawable.jarvis_drive_golden_reference)
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                alpha = (opacityPercent.coerceIn(0, 100)) / 100f,
                modifier = Modifier
                    .offset(x = xDp, y = yDp)
                    .size(widthDp, heightDp),
            )
        }
    }
}

/** Default opacity for the reference overlay when first enabled (spec §3: "default 50%"). */
const val REFERENCE_OVERLAY_DEFAULT_OPACITY_PERCENT = 50
