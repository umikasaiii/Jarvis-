package com.simone.jarvismobile.core.weather.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE E §20/§21. Pins the versioned observed-outcome contract's
 * mapping to the binary [ObservedOutcome] truth [WeatherAlertReplay
 * .aggregate] scores against — written BEFORE any real qualification
 * scoring happens, exactly as §20 requires ("do not move the goalposts
 * after seeing results").
 */
class WeatherObservedOutcomeTest {

    @Test fun `RAIN_OBSERVED maps to HAZARD_OCCURRED`() {
        assertEquals(ObservedOutcome.HAZARD_OCCURRED, WeatherObservedOutcome.RAIN_OBSERVED.toAggregationTruth())
    }

    @Test fun `STORM_OBSERVED maps to HAZARD_OCCURRED`() {
        assertEquals(ObservedOutcome.HAZARD_OCCURRED, WeatherObservedOutcome.STORM_OBSERVED.toAggregationTruth())
    }

    @Test fun `NO_MEANINGFUL_RAIN_OBSERVED maps to NO_HAZARD_OCCURRED`() {
        assertEquals(ObservedOutcome.NO_HAZARD_OCCURRED, WeatherObservedOutcome.NO_MEANINGFUL_RAIN_OBSERVED.toAggregationTruth())
    }

    @Test fun `SNOW_MIXED maps to NO_HAZARD_OCCURRED - snow is never equivalent to a rain hazard`() {
        // § §20 — "do not call trace precipitation automatically equivalent
        // to the proactive product threshold": the product itself never
        // alerts for snow/mixed precipitation, so a snow-only day is a
        // genuine true negative for the rain alert being scored, not an
        // excluded/unknown case and not a false negative.
        assertEquals(ObservedOutcome.NO_HAZARD_OCCURRED, WeatherObservedOutcome.SNOW_MIXED.toAggregationTruth())
    }

    @Test fun `OBSERVATION_UNKNOWN maps to null - never silently counted as a true negative`() {
        assertNull(WeatherObservedOutcome.OBSERVATION_UNKNOWN.toAggregationTruth())
    }

    @Test fun `every enum value has an explicit mapping decision`() {
        // A future sixth value would fail to compile the exhaustive `when`
        // in toAggregationTruth() before it could ever silently fall
        // through - this test just pins the count as a second, explicit
        // guard against that class of regression.
        assertEquals(5, WeatherObservedOutcome.entries.size)
    }
}
