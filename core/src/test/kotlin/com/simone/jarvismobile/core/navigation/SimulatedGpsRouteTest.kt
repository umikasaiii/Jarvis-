package com.simone.jarvismobile.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedGpsRouteTest {

    private val origin = LatLng(41.9, 12.5)
    private val route = SimulatedGpsRoute(origin)

    @Test
    fun `at time zero the fix is exactly the origin`() {
        val fix = route.fixAt(0L)
        assertEquals(origin.lat, fix.location.lat, 1e-9)
        assertEquals(origin.lon, fix.location.lon, 1e-9)
    }

    @Test
    fun `the fix always carries the requested speed`() {
        val fix = route.fixAt(5_000L, speedMps = 20f)
        assertEquals(20f, fix.speedMps)
    }

    @Test
    fun `bearing is always a valid compass heading`() {
        val fix = route.fixAt(15_000L)
        assertTrue(fix.bearingDegrees!! in 0f..360f)
    }

    @Test
    fun `the loop returns arbitrarily close to the start after one full perimeter`() {
        // 4 legs x 300 m at the default 12 m/s speed: perimeter / speed = period.
        val perimeterSeconds = (300.0 * 4) / 12.0
        val afterOneLoop = route.fixAt((perimeterSeconds * 1000).toLong())
        val distance = Geo.distanceMeters(origin, afterOneLoop.location)
        assertTrue(distance < 1.0, "expected to be back near the origin, was ${distance}m away")
    }

    @Test
    fun `accuracy is always a small, plausible value`() {
        assertEquals(5f, route.fixAt(1_234L).accuracyMeters)
    }

    @Test
    fun `never crashes for a very large elapsed time`() {
        // Just needs to not throw; the modulo wrap should keep it well-defined forever.
        route.fixAt(Long.MAX_VALUE / 2)
    }
}
