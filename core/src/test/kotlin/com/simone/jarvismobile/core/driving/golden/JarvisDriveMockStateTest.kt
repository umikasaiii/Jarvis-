package com.simone.jarvismobile.core.driving.golden

import com.simone.jarvismobile.core.driving.DrivingVoiceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JarvisDriveMockStateTest {

    @Test
    fun `every card the golden reference shows has mock data to render`() {
        val s = JarvisDriveMockState.state
        assertTrue(s.navigationActive)
        assertEquals(DrivingVoiceState.LISTENING, s.voiceState)
        assertTrue(s.nextManeuver != null, "maneuver card would render empty")
        assertTrue(s.remainingMinutes != null, "eta bar minutes would render empty")
        assertTrue(s.remainingDistanceMeters != null, "eta bar distance would render empty")
        assertTrue(s.currentSpeedKmh != null, "speed card would render empty")
        assertTrue(s.speedLimitKmh != null, "speed card limit would render empty")
        assertTrue(s.media != null, "music card would render empty")
        assertEquals(2, s.messages.size, "messages card should show the same two senders as the reference")
    }
}
