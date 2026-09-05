package com.simone.jarvismobile.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * § FASE 2A.7 RELEASE GATE 3 — "MAI modifica silenziosa della data
 * richiesta": pins that an out-of-range day offset resolves to an honest
 * [WeatherDaysAhead.Resolution.OutOfRange] carrying the REAL requested
 * value, never silently substituted by an in-range one.
 */
class WeatherDaysAheadTest {

    @Test
    fun `today and each of the three supported days ahead are all supported`() {
        for (day in 0..3) {
            assertEquals(WeatherDaysAhead.Resolution.Supported(day), WeatherDaysAhead.resolve(day))
        }
    }

    @Test
    fun `ten days ahead is out of range, carrying the real requested value`() {
        assertEquals(WeatherDaysAhead.Resolution.OutOfRange(10), WeatherDaysAhead.resolve(10))
    }

    @Test
    fun `four days ahead - one past the supported range - is out of range too`() {
        assertEquals(WeatherDaysAhead.Resolution.OutOfRange(4), WeatherDaysAhead.resolve(4))
    }

    @Test
    fun `a negative offset (a date already in the past) is out of range, never coerced to today`() {
        assertEquals(WeatherDaysAhead.Resolution.OutOfRange(-1), WeatherDaysAhead.resolve(-1))
    }

    @Test
    fun `out of range never reports back an in-range value instead of the real one`() {
        val resolution = WeatherDaysAhead.resolve(10) as WeatherDaysAhead.Resolution.OutOfRange
        assertEquals(10, resolution.requestedDaysAhead)
    }
}
