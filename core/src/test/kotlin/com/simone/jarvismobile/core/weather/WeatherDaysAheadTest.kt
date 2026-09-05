package com.simone.jarvismobile.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * § FASE 2A.7 RELEASE GATE 3 — "MAI modifica silenziosa della data
 * richiesta": pins that an out-of-range day offset resolves to an honest
 * [WeatherDaysAhead.Resolution.OutOfRange] carrying the REAL requested
 * value, never silently substituted by an in-range one.
 *
 * § FASE 2A.8 RELEASE GATE H — the supported range was raised from 3 to 16
 * (chat horizon, "che tempo farà tra 10 giorni?" no longer rejected) while
 * the home dashboard's own separate 4-day window is untouched code — see
 * [WeatherDaysAhead]'s own doc comment.
 */
class WeatherDaysAheadTest {

    @Test
    fun `today and every day up to the real supported horizon are all supported`() {
        for (day in 0..16) {
            assertEquals(WeatherDaysAhead.Resolution.Supported(day), WeatherDaysAhead.resolve(day))
        }
    }

    @Test
    fun `ten days ahead is now genuinely supported, not silently rejected`() {
        assertEquals(WeatherDaysAhead.Resolution.Supported(10), WeatherDaysAhead.resolve(10))
    }

    @Test
    fun `seventeen days ahead is out of range, carrying the real requested value`() {
        assertEquals(WeatherDaysAhead.Resolution.OutOfRange(17), WeatherDaysAhead.resolve(17))
    }

    @Test
    fun `a negative offset (a date already in the past) is out of range, never coerced to today`() {
        assertEquals(WeatherDaysAhead.Resolution.OutOfRange(-1), WeatherDaysAhead.resolve(-1))
    }

    @Test
    fun `out of range never reports back an in-range value instead of the real one`() {
        val resolution = WeatherDaysAhead.resolve(30) as WeatherDaysAhead.Resolution.OutOfRange
        assertEquals(30, resolution.requestedDaysAhead)
    }
}
