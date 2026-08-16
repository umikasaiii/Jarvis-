package com.simone.jarvismobile.core.driving

import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.Maneuver
import com.simone.jarvismobile.core.navigation.ManeuverType
import com.simone.jarvismobile.core.navigation.NavigationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DrivingUiStateTest {

    @Test
    fun `default state has no navigation and stays on the external overlay`() {
        val state = DrivingUiState()
        assertEquals(DrivingNavigationMode.EXTERNAL_MAPS_OVERLAY, state.navigationMode)
        assertEquals(false, state.navigationActive)
        assertNull(state.nextManeuver)
    }

    @Test
    fun `togglePanel opens a closed panel and closes an open one, same as the overlay's toggle`() {
        val opened = DrivingUiState().togglePanel(DrivingExpandedPanel.MEDIA)
        assertEquals(DrivingExpandedPanel.MEDIA, opened.expandedPanel)

        val closed = opened.togglePanel(DrivingExpandedPanel.MEDIA)
        assertEquals(DrivingExpandedPanel.NONE, closed.expandedPanel)

        val switched = opened.togglePanel(DrivingExpandedPanel.MESSAGES)
        assertEquals(DrivingExpandedPanel.MESSAGES, switched.expandedPanel)
    }

    @Test
    fun `incoming call collapses whatever panel was open`() {
        val withMediaOpen = DrivingUiState().togglePanel(DrivingExpandedPanel.MEDIA)
        val duringCall = withMediaOpen.withIncomingCall(true)

        assertEquals(true, duringCall.incomingCall)
        assertEquals(DrivingExpandedPanel.NONE, duringCall.expandedPanel)
    }

    @Test
    fun `call ending restores exactly the panel that was open before it, not something new`() {
        val withMessagesOpen = DrivingUiState().togglePanel(DrivingExpandedPanel.MESSAGES)
        val afterCall = withMessagesOpen.withIncomingCall(true).withIncomingCall(false)

        assertEquals(false, afterCall.incomingCall)
        assertEquals(DrivingExpandedPanel.MESSAGES, afterCall.expandedPanel)
    }

    @Test
    fun `call ending with nothing open before it restores to NONE, not MEDIA or MESSAGES`() {
        val afterCall = DrivingUiState().withIncomingCall(true).withIncomingCall(false)
        assertEquals(DrivingExpandedPanel.NONE, afterCall.expandedPanel)
    }

    @Test
    fun `togglePanel is a no-op while a call is active`() {
        val duringCall = DrivingUiState().withIncomingCall(true)
        val attempted = duringCall.togglePanel(DrivingExpandedPanel.MEDIA)
        assertEquals(DrivingExpandedPanel.NONE, attempted.expandedPanel)
    }

    @Test
    fun `redundant withIncomingCall calls are idempotent no-ops`() {
        val once = DrivingUiState().togglePanel(DrivingExpandedPanel.MEDIA).withIncomingCall(true)
        val twice = once.withIncomingCall(true)
        assertEquals(once, twice)
    }

    @Test
    fun `toManeuverUiModel carries the live distance, not a stale one from the maneuver itself`() {
        val maneuver = Maneuver(ManeuverType.TURN_LEFT, LatLng(41.9, 12.5), geometryIndex = 3, roadName = "Via Roma")
        val progress = NavigationProgress(
            distanceToManeuverMeters = 250.0,
            nextManeuver = maneuver,
            remainingDistanceMeters = 1200.0,
            etaSeconds = 90.0,
        )

        val ui = progress.toManeuverUiModel()

        assertEquals(ManeuverType.TURN_LEFT, ui?.type)
        assertEquals("Via Roma", ui?.roadName)
        assertEquals(250, ui?.distanceMeters)
    }

    @Test
    fun `toManeuverUiModel is null with no upcoming maneuver, so the card can hide itself`() {
        val progress = NavigationProgress(
            distanceToManeuverMeters = 0.0,
            nextManeuver = null,
            remainingDistanceMeters = 0.0,
            etaSeconds = 0.0,
        )
        assertNull(progress.toManeuverUiModel())
    }

    @Test
    fun `metersPerSecondToKmh matches the standard 3_6 conversion`() {
        assertEquals(36, metersPerSecondToKmh(10f))
        assertEquals(0, metersPerSecondToKmh(0f))
    }
}
