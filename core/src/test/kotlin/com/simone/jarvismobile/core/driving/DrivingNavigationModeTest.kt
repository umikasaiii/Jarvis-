package com.simone.jarvismobile.core.driving

import kotlin.test.Test
import kotlin.test.assertEquals

class DrivingNavigationModeTest {

    @Test
    fun `external maps overlay is the first declared (default) mode`() {
        // The default must stay EXTERNAL_MAPS_OVERLAY until internal navigation
        // is complete (spec §1/§21) — pinning ordinal 0 catches an accidental
        // reorder that would silently flip every caller's default.
        assertEquals(0, DrivingNavigationMode.EXTERNAL_MAPS_OVERLAY.ordinal)
    }

    @Test
    fun `exactly two modes exist`() {
        assertEquals(
            setOf(DrivingNavigationMode.EXTERNAL_MAPS_OVERLAY, DrivingNavigationMode.INTERNAL_JARVIS_NAVIGATION),
            DrivingNavigationMode.entries.toSet(),
        )
    }
}
