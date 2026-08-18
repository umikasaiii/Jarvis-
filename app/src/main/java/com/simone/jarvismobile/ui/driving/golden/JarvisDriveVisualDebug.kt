package com.simone.jarvismobile.ui.driving.golden

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simone.jarvismobile.core.driving.golden.GoldenPoint
import com.simone.jarvismobile.core.driving.golden.JarvisDriveGoldenLayout

/**
 * Debug-only measurement overlay (spec §4): bounding boxes, names,
 * coordinates and size for every [JarvisDriveGoldenLayout] component, plus
 * center lines, the vehicle anchor and the map viewport outline — everything
 * needed to check a real device's layout against the fractions measured
 * from the golden reference, without eyeballing.
 *
 * Same non-interactive contract as [JarvisDriveReferenceOverlay]: no click
 * or pointer-input modifiers, so it never blocks a real tap, and it is
 * excluded from screen readers via [invisibleToUser]. The caller decides
 * whether to place it at all — this composable has no `BuildConfig.DEBUG`
 * check of its own, same as [JarvisDriveReferenceOverlay].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun JarvisDriveVisualDebug(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().semantics { invisibleToUser() }) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Center lines.
            drawLine(DebugGridColor, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1f)
            drawLine(DebugGridColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)

            // Reference grid, one line per 10% of each axis — cheap orientation aid.
            for (i in 1..9) {
                val fx = i / 10f
                drawLine(DebugGridColor.copy(alpha = 0.25f), Offset(w * fx, 0f), Offset(w * fx, h), strokeWidth = 0.5f)
                drawLine(DebugGridColor.copy(alpha = 0.25f), Offset(0f, h * fx), Offset(w, h * fx), strokeWidth = 0.5f)
            }

            // Map viewport outline (spec §4 "map viewport").
            val viewport = JarvisDriveGoldenLayout.MapViewport.resolve(w, h)
            drawRect(
                color = MapViewportColor,
                topLeft = Offset(viewport.x, viewport.y),
                size = Size(viewport.width, viewport.height),
                style = Stroke(width = 2f),
            )

            // Every named component's bounding box.
            JarvisDriveGoldenLayout.namedRects.forEach { (_, rect) ->
                if (rect === JarvisDriveGoldenLayout.MapViewport) return@forEach
                val r = rect.resolve(w, h)
                drawRect(
                    color = ComponentBoxColor,
                    topLeft = Offset(r.x, r.y),
                    size = Size(r.width, r.height),
                    style = Stroke(width = 2f),
                )
            }

            // Named points (vehicle anchor, turn marker, round buttons) as small crosses.
            JarvisDriveGoldenLayout.namedPoints.forEach { (_, point) ->
                val p = point.resolve(w, h)
                val armPx = 12f
                drawLine(PointColor, Offset(p.x - armPx, p.y), Offset(p.x + armPx, p.y), strokeWidth = 2f)
                drawLine(PointColor, Offset(p.x, p.y - armPx), Offset(p.x, p.y + armPx), strokeWidth = 2f)
            }
        }

        // Labels: one small Text per named rect, anchored at its top-left corner in dp —
        // Canvas alone can't draw text without a TextMeasurer, and a handful of small
        // Compose Text nodes is simpler and cheaper than wiring one up for a debug tool.
        JarvisDriveGoldenLayout.namedRects.forEach { (name, rect) ->
            if (rect === JarvisDriveGoldenLayout.MapViewport) return@forEach
            val r = rect.resolve(containerWidthPx, containerHeightPx)
            val xDp: Dp = with(density) { r.x.toDp() }
            val yDp: Dp = with(density) { r.y.toDp() }
            val wPx = r.width
            val hPx = r.height
            DebugLabel(
                text = "$name\n${wPx.toInt()}×${hPx.toInt()}px @ (${r.x.toInt()},${r.y.toInt()})",
                xDp = xDp,
                yDp = yDp,
            )
        }
        JarvisDriveGoldenLayout.namedPoints.forEach { (name, point) ->
            labelForPoint(point, name, containerWidthPx, containerHeightPx, density)
        }
    }
}

@Composable
private fun labelForPoint(point: GoldenPoint, name: String, containerWidthPx: Float, containerHeightPx: Float, density: Density) {
    val p = point.resolve(containerWidthPx, containerHeightPx)
    val xDp: Dp = with(density) { p.x.toDp() }
    val yDp: Dp = with(density) { p.y.toDp() }
    DebugLabel(text = name, xDp = xDp, yDp = yDp)
}

@Composable
private fun DebugLabel(text: String, xDp: Dp, yDp: Dp) {
    Box(Modifier.offset(x = xDp + 2.dp, y = yDp + 2.dp)) {
        Text(
            text,
            color = LabelTextColor,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            textAlign = TextAlign.Start,
        )
    }
}

private val DebugGridColor = Color(0x662ECC71)
private val ComponentBoxColor = Color(0xFF2ECC71)
private val MapViewportColor = Color(0xFF3AC0FF)
private val PointColor = Color(0xFFFFC107)
private val LabelTextColor = Color(0xFF2ECC71)
