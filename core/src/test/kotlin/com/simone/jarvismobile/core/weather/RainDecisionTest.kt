package com.simone.jarvismobile.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RainDecisionTest {

    @Test fun `a null probability stays unknown, never no-rain`() {
        assertNull(RainDecision.fromProbabilityPercent(null))
    }

    @Test fun `at or above the threshold is rain`() {
        assertEquals(true, RainDecision.fromProbabilityPercent(50))
        assertEquals(true, RainDecision.fromProbabilityPercent(100))
    }

    @Test fun `below the threshold is not rain`() {
        assertEquals(false, RainDecision.fromProbabilityPercent(0))
        assertEquals(false, RainDecision.fromProbabilityPercent(49))
    }

    @Test fun `an out-of-range probability is clamped rather than misread`() {
        assertEquals(true, RainDecision.fromProbabilityPercent(150))
        assertEquals(false, RainDecision.fromProbabilityPercent(-10))
    }
}
