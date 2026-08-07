package com.simone.jarvismobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val Cyan = Color(0xFF12D9FF)

/**
 * A dashboard card, drawn rather than blitted.
 *
 * It used to be a 1536×1024 PNG scaled into place, which caused two visible
 * faults at once: the artwork carried a dark area outside its rounded frame that
 * painted as a black border, and scaling a bitmap that large made the cards go
 * soft during a fling and snap sharp on release.
 *
 * Drawing the frame costs nothing to scale, has no edges to leak, and matches
 * the design brief — background, border, glow and corner marks are separate
 * layers, so any one of them can change without touching an image file.
 */
@Composable
fun JarvisCard(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    accent: Color = Cyan,
    contentPadding: Dp = 18.dp,
    badge: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.drawBehind {
            val r = CornerRadius(radius.toPx(), radius.toPx())
            val stroke = 1.dp.toPx()
            val inset = stroke / 2f
            val body = Size(size.width - stroke, size.height - stroke)
            val at = Offset(inset, inset)

            // Glass body: a vertical gradient over whatever is behind, never an
            // opaque slab, so the background stays visible through the card.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xB30C2233), Color(0x99050F19)),
                ),
                topLeft = at,
                size = body,
                cornerRadius = r,
            )

            // Rim.
            drawRoundRect(
                color = accent.copy(alpha = 0.30f),
                topLeft = at,
                size = body,
                cornerRadius = r,
                style = Stroke(width = stroke),
            )

            // Bright bars centred top and bottom, as in the reference frame.
            val barW = size.width * 0.22f
            val barX = (size.width - barW) / 2f
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, accent, Color.Transparent),
                    startX = barX,
                    endX = barX + barW,
                ),
                start = Offset(barX, inset),
                end = Offset(barX + barW, inset),
                strokeWidth = 2.dp.toPx(),
            )
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, accent.copy(alpha = 0.7f), Color.Transparent),
                    startX = barX,
                    endX = barX + barW,
                ),
                start = Offset(barX, size.height - inset),
                end = Offset(barX + barW, size.height - inset),
                strokeWidth = 2.dp.toPx(),
            )

            // Short lit marks just inside each corner.
            val m = 22.dp.toPx()
            val edge = accent.copy(alpha = 0.85f)
            val rr = radius.toPx()
            drawLine(edge, Offset(inset, rr), Offset(inset, rr + m), 2.dp.toPx())
            drawLine(edge, Offset(size.width - inset, rr), Offset(size.width - inset, rr + m), 2.dp.toPx())
            drawLine(
                edge.copy(alpha = 0.5f),
                Offset(inset, size.height - rr),
                Offset(inset, size.height - rr - m),
                2.dp.toPx(),
            )
            drawLine(
                edge.copy(alpha = 0.5f),
                Offset(size.width - inset, size.height - rr),
                Offset(size.width - inset, size.height - rr - m),
                2.dp.toPx(),
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            content = content,
        )
        if (badge != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 14.dp)) { badge() }
        }
    }
}
