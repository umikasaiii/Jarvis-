package com.simone.jarvismobile.core.driving.golden

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoldenRectTest {

    @Test
    fun `resolving against a container scales x y width height independently`() {
        val rect = GoldenRect(0.1f, 0.2f, 0.5f, 0.25f)
        val resolved = rect.resolve(containerWidth = 1000f, containerHeight = 2000f)
        assertEquals(100f, resolved.x)
        assertEquals(400f, resolved.y)
        assertEquals(500f, resolved.width)
        assertEquals(500f, resolved.height)
    }

    @Test
    fun `a fraction outside 0 to 1 is rejected rather than silently clamped`() {
        assertFailsWith<IllegalArgumentException> { GoldenRect(-0.1f, 0f, 0.5f, 0.5f) }
        assertFailsWith<IllegalArgumentException> { GoldenRect(0f, 0f, 1.1f, 0.5f) }
    }

    @Test
    fun `a rect that overflows its own container is rejected`() {
        assertFailsWith<IllegalArgumentException> { GoldenRect(0.6f, 0f, 0.5f, 0.1f) }
        assertFailsWith<IllegalArgumentException> { GoldenRect(0f, 0.6f, 0.1f, 0.5f) }
    }

    @Test
    fun `a rect touching exactly the far edge is allowed`() {
        GoldenRect(0.5f, 0.5f, 0.5f, 0.5f) // must not throw
    }
}

class GoldenPointTest {
    @Test
    fun `resolving scales x and y by the container size`() {
        val point = GoldenPoint(0.25f, 0.75f)
        val resolved = point.resolve(800f, 400f)
        assertEquals(200f, resolved.x)
        assertEquals(300f, resolved.y)
    }
}

class JarvisDriveGoldenLayoutTest {

    @Test
    fun `every named rect and point is a valid fraction of the reference`() {
        JarvisDriveGoldenLayout.namedRects.forEach { (name, rect) ->
            assertTrue(rect.xFraction in 0f..1f, "$name xFraction out of range")
            assertTrue(rect.yFraction in 0f..1f, "$name yFraction out of range")
            assertTrue(rect.xFraction + rect.widthFraction <= 1f + 1e-4f, "$name overflows right edge")
            assertTrue(rect.yFraction + rect.heightFraction <= 1f + 1e-4f, "$name overflows bottom edge")
        }
        JarvisDriveGoldenLayout.namedPoints.forEach { (name, point) ->
            assertTrue(point.xFraction in 0f..1f, "$name xFraction out of range")
            assertTrue(point.yFraction in 0f..1f, "$name yFraction out of range")
        }
    }

    @Test
    fun `maneuver card and speed card sit side by side without overlapping`() {
        val maneuverRight = JarvisDriveGoldenLayout.ManeuverCard.xFraction + JarvisDriveGoldenLayout.ManeuverCard.widthFraction
        assertTrue(maneuverRight <= JarvisDriveGoldenLayout.SpeedCard.xFraction, "ManeuverCard overlaps SpeedCard")
    }

    @Test
    fun `eta bar sits below the maneuver and speed card row`() {
        val maneuverBottom = JarvisDriveGoldenLayout.ManeuverCard.yFraction + JarvisDriveGoldenLayout.ManeuverCard.heightFraction
        assertTrue(JarvisDriveGoldenLayout.EtaBar.yFraction >= maneuverBottom - 1e-4f)
    }

    @Test
    fun `messages and music cards sit on opposite sides of the screen`() {
        assertTrue(JarvisDriveGoldenLayout.MessagesCard.xFraction < 0.5f)
        assertTrue(JarvisDriveGoldenLayout.MusicCard.xFraction > 0.5f)
    }

    @Test
    fun `vehicle anchor sits below screen center, matching a heading-up navigation camera`() {
        assertTrue(JarvisDriveGoldenLayout.VehicleAnchor.yFraction > 0.6f)
        assertTrue(JarvisDriveGoldenLayout.VehicleAnchor.yFraction < 0.75f)
    }

    @Test
    fun `app button and layers button are mirrored near the left and right edges`() {
        assertTrue(JarvisDriveGoldenLayout.AppButtonCenter.xFraction < 0.15f)
        assertTrue(JarvisDriveGoldenLayout.LayersButtonCenter.xFraction > 0.85f)
        assertEquals(
            JarvisDriveGoldenLayout.AppButtonCenter.yFraction,
            JarvisDriveGoldenLayout.LayersButtonCenter.yFraction,
        )
    }

    @Test
    fun `fitReference letterboxes a wider container, centering vertically band to band`() {
        // Container much wider than the reference's own aspect ratio.
        val fitted = JarvisDriveGoldenLayout.fitReference(containerWidth = 2000f, containerHeight = 1620f)
        assertApprox(1620f * (JarvisDriveGoldenLayout.REFERENCE_WIDTH / JarvisDriveGoldenLayout.REFERENCE_HEIGHT), fitted.width, 0.01f)
        assertApprox(1620f, fitted.height, 0.01f)
        assertTrue(fitted.x > 0f, "should be centered with side bars")
        assertApprox(0f, fitted.y, 0.01f)
    }

    @Test
    fun `fitReference pillarboxes a narrower container, centering horizontally`() {
        val fitted = JarvisDriveGoldenLayout.fitReference(containerWidth = 400f, containerHeight = 3000f)
        assertApprox(400f, fitted.width, 0.01f)
        assertTrue(fitted.y > 0f, "should be centered with top/bottom bars")
    }

    @Test
    fun `fitReference matching the reference aspect ratio exactly fills with no bars`() {
        val fitted = JarvisDriveGoldenLayout.fitReference(
            JarvisDriveGoldenLayout.REFERENCE_WIDTH * 2f,
            JarvisDriveGoldenLayout.REFERENCE_HEIGHT * 2f,
        )
        assertApprox(0f, fitted.x, 0.01f)
        assertApprox(0f, fitted.y, 0.01f)
        assertApprox(2f, fitted.scale, 0.001f)
    }
}

private fun assertApprox(expected: Float, actual: Float, tolerance: Float) {
    assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
}
