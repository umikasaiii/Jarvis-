package com.simone.jarvismobile.core.driving

import com.simone.jarvismobile.core.navigation.GpsFix
import com.simone.jarvismobile.core.navigation.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrivingCameraControllerTest {

    private fun fixAt(speedMps: Float?, bearingDegrees: Float? = 90f) = GpsFix(
        location = LatLng(41.9, 12.5),
        accuracyMeters = 5f,
        speedMps = speedMps,
        bearingDegrees = bearingDegrees,
        timestampMs = 0L,
    )

    @Test
    fun `no fix means no camera state, caller keeps the previous one`() {
        assertNull(DrivingCameraController.forFollow(null))
    }

    @Test
    fun `bearing follows the GPS heading exactly`() {
        val state = DrivingCameraController.forFollow(fixAt(speedMps = 10f, bearingDegrees = 271.5f))
        assertEquals(271.5f, state?.bearingDegrees)
    }

    @Test
    fun `missing bearing defaults to zero rather than crashing`() {
        val state = DrivingCameraController.forFollow(fixAt(speedMps = 10f, bearingDegrees = null))
        assertEquals(0f, state?.bearingDegrees)
    }

    @Test
    fun `standing still zooms in the most`() {
        val state = DrivingCameraController.forFollow(fixAt(speedMps = 0f))
        assertEquals(18.0, state?.zoom)
    }

    @Test
    fun `at or above the zoom-out speed the camera reaches its widest`() {
        val at90kmh = DrivingCameraController.forFollow(fixAt(speedMps = 25f)) // 90 km/h
        val faster = DrivingCameraController.forFollow(fixAt(speedMps = 40f)) // well above 90 km/h
        assertEquals(15.5, at90kmh?.zoom)
        assertEquals(15.5, faster?.zoom)
    }

    @Test
    fun `zoom decreases monotonically as speed increases`() {
        val slow = DrivingCameraController.forFollow(fixAt(speedMps = 5f))!!
        val fast = DrivingCameraController.forFollow(fixAt(speedMps = 20f))!!
        assertTrue(fast.zoom < slow.zoom)
    }

    @Test
    fun `tilt is a fixed navigation angle regardless of speed`() {
        assertEquals(55f, DrivingCameraController.forFollow(fixAt(speedMps = 0f))?.tiltDegrees)
        assertEquals(55f, DrivingCameraController.forFollow(fixAt(speedMps = 30f))?.tiltDegrees)
    }

    @Test
    fun `no distance to maneuver leaves the speed-based zoom untouched`() {
        val withDistance = DrivingCameraController.forFollow(fixAt(speedMps = 20f), distanceToManeuverMeters = null)
        val without = DrivingCameraController.forFollow(fixAt(speedMps = 20f))
        assertEquals(without?.zoom, withDistance?.zoom)
    }

    @Test
    fun `zoom boosts in as the next maneuver gets closer`() {
        val far = DrivingCameraController.forFollow(fixAt(speedMps = 20f), distanceToManeuverMeters = 500.0)!!
        val near = DrivingCameraController.forFollow(fixAt(speedMps = 20f), distanceToManeuverMeters = 90.0)!!
        val atManeuver = DrivingCameraController.forFollow(fixAt(speedMps = 20f), distanceToManeuverMeters = 0.0)!!
        assertTrue(near.zoom > far.zoom)
        assertTrue(atManeuver.zoom > near.zoom)
    }

    @Test
    fun `far beyond the maneuver zoom-in distance behaves as if there were no maneuver at all`() {
        val far = DrivingCameraController.forFollow(fixAt(speedMps = 20f), distanceToManeuverMeters = 500.0)
        val none = DrivingCameraController.forFollow(fixAt(speedMps = 20f), distanceToManeuverMeters = null)
        assertEquals(none?.zoom, far?.zoom)
    }

    @Test
    fun `the maneuver zoom boost still respects the standstill zoom ceiling plus its own boost`() {
        val state = DrivingCameraController.forFollow(fixAt(speedMps = 0f), distanceToManeuverMeters = 0.0)!!
        assertEquals(19.0, state.zoom) // 18.0 (max, standing still) + 1.0 (full proximity boost)
    }
}

class DrivingCameraUiStateTest {

    @Test
    fun `default state is FOLLOW`() {
        assertEquals(DrivingCameraMode.FOLLOW, DrivingCameraUiState().mode)
    }

    @Test
    fun `a user pan while following drops into FREE`() {
        val free = DrivingCameraUiState().userPanned()
        assertEquals(DrivingCameraMode.FREE, free.mode)
    }

    @Test
    fun `panning again while already free is a no-op`() {
        val free = DrivingCameraUiState().userPanned()
        assertEquals(free, free.userPanned())
    }

    @Test
    fun `recenter always returns to FOLLOW, from FREE or OVERVIEW`() {
        assertEquals(DrivingCameraMode.FOLLOW, DrivingCameraUiState().userPanned().recenter().mode)
        assertEquals(DrivingCameraMode.FOLLOW, DrivingCameraUiState().overview().recenter().mode)
    }

    @Test
    fun `overview is reachable from any mode`() {
        assertEquals(DrivingCameraMode.OVERVIEW, DrivingCameraUiState().overview().mode)
        assertEquals(DrivingCameraMode.OVERVIEW, DrivingCameraUiState().userPanned().overview().mode)
    }
}
