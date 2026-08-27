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

    @Test fun `fromForecast needs both signals present, else unknown`() {
        assertNull(RainDecision.fromForecast(null, 5.0))
        assertNull(RainDecision.fromForecast(80, null))
        assertNull(RainDecision.fromForecast(null, null))
    }

    @Test fun `fromForecast rejects a high probability with no real accumulation`() {
        // This is exactly the near-nightly false positive: a brief-shower spike
        // pushes the max probability past 50 on an otherwise dry day.
        assertEquals(false, RainDecision.fromForecast(80, 0.0))
        assertEquals(false, RainDecision.fromForecast(80, 0.1))
    }

    @Test fun `fromForecast rejects real accumulation with a low mean probability`() {
        assertEquals(false, RainDecision.fromForecast(20, 3.0))
    }

    @Test fun `fromForecast agrees only when both signals clear their threshold`() {
        assertEquals(true, RainDecision.fromForecast(50, 0.2))
        assertEquals(true, RainDecision.fromForecast(90, 5.0))
    }
}
