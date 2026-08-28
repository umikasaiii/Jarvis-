package com.simone.jarvismobile.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RainDecisionTest {

    @Test fun `an unknown category stays unknown, never no-rain`() {
        assertNull(RainDecision.isRainDay(null, 5.0))
        assertNull(RainDecision.isRainDay(null, null))
    }

    @Test fun `clear, partly cloudy and cloudy are never rain`() {
        assertEquals(false, RainDecision.isRainDay(WeatherCategory.CLEAR, null))
        assertEquals(false, RainDecision.isRainDay(WeatherCategory.PARTLY_CLOUDY, null))
        assertEquals(false, RainDecision.isRainDay(WeatherCategory.CLOUDY, null))
    }

    @Test fun `thunderstorm is always rain regardless of accumulation`() {
        assertEquals(true, RainDecision.isRainDay(WeatherCategory.THUNDERSTORM, null))
        assertEquals(true, RainDecision.isRainDay(WeatherCategory.THUNDERSTORM, 0.0))
    }

    @Test fun `a rain-coded day needs real accumulation to count`() {
        assertEquals(false, RainDecision.isRainDay(WeatherCategory.RAIN, 0.0))
        assertEquals(false, RainDecision.isRainDay(WeatherCategory.RAIN, 0.1))
        assertEquals(true, RainDecision.isRainDay(WeatherCategory.RAIN, 0.2))
        assertEquals(true, RainDecision.isRainDay(WeatherCategory.RAIN, 5.0))
    }

    @Test fun `a rain-coded day with missing millimetres stays unknown`() {
        assertNull(RainDecision.isRainDay(WeatherCategory.RAIN, null))
    }
}
