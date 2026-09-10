package com.simone.jarvismobile.core.weather

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. Deterministic
 * forecast fixtures exercising the bounded hazard decision, mirroring the
 * spec's own required scenarios.
 */
class WeatherAlertPolicyTest {

    // --- tomorrow no rain → no alert -------------------------------------

    @Test fun `clear tomorrow yields NO_ALERT`() {
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.CLEAR, null)
        assertEquals(WeatherAlertEvaluation.Decided(WeatherHazard.NO_ALERT), result)
    }

    @Test fun `cloudy tomorrow yields NO_ALERT`() {
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.CLOUDY, 0.0)
        assertEquals(WeatherAlertEvaluation.Decided(WeatherHazard.NO_ALERT), result)
    }

    // --- tomorrow meaningful rain → alert ---------------------------------

    @Test fun `meaningful rain yields RAIN_EXPECTED`() {
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.RAIN, 3.5)
        assertEquals(WeatherAlertEvaluation.Decided(WeatherHazard.RAIN_EXPECTED), result)
    }

    @Test fun `heavy accumulation yields HEAVY_RAIN, not the generic tier`() {
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.RAIN, 15.0)
        assertEquals(WeatherAlertEvaluation.Decided(WeatherHazard.HEAVY_RAIN), result)
    }

    // --- tomorrow thunderstorm → alert, independent of mm ------------------

    @Test fun `thunderstorm always alerts regardless of millimeters`() {
        assertEquals(
            WeatherAlertEvaluation.Decided(WeatherHazard.THUNDERSTORM),
            WeatherAlertPolicy.evaluate(WeatherCategory.THUNDERSTORM, 0.0),
        )
    }

    @Test fun `thunderstorm must not depend on rain-description text - it takes no String at all`() {
        // The function signature itself proves this: only WeatherCategory + Double?.
        // A missing millimeters field never demotes/omits a thunderstorm.
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.THUNDERSTORM, null)
        assertEquals(WeatherAlertEvaluation.Decided(WeatherHazard.THUNDERSTORM), result)
    }

    @Test fun `alert decision is unchanged across repeated calls with the same structured input`() {
        // Proves determinism: no hidden state, no randomness, no dependency
        // on how the forecast might later be phrased in natural language.
        val a = WeatherAlertPolicy.evaluate(WeatherCategory.RAIN, 4.0)
        val b = WeatherAlertPolicy.evaluate(WeatherCategory.RAIN, 4.0)
        assertEquals(a, b)
    }

    // --- unknown / missing data never invents an answer --------------------

    @Test fun `null category is Unknown, never NO_ALERT`() {
        val result = WeatherAlertPolicy.evaluate(null, null)
        assertIs<WeatherAlertEvaluation.Unknown>(result)
    }

    @Test fun `rain category with missing millimeters is Unknown, never assumed meaningless`() {
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.RAIN, null)
        assertIs<WeatherAlertEvaluation.Unknown>(result)
    }

    // --- threshold boundaries -----------------------------------------------

    @Test fun `just under the meaningful threshold is NO_ALERT`() {
        val justUnder = WeatherAlertPolicy.MIN_MEANINGFUL_MILLIMETERS - 0.01
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.RAIN, justUnder)
        assertEquals(WeatherAlertEvaluation.Decided(WeatherHazard.NO_ALERT), result)
    }

    @Test fun `exactly the meaningful threshold is RAIN_EXPECTED`() {
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.RAIN, WeatherAlertPolicy.MIN_MEANINGFUL_MILLIMETERS)
        assertEquals(WeatherAlertEvaluation.Decided(WeatherHazard.RAIN_EXPECTED), result)
    }

    @Test fun `just under the heavy threshold stays RAIN_EXPECTED`() {
        val justUnder = WeatherAlertPolicy.HEAVY_RAIN_MILLIMETERS - 0.01
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.RAIN, justUnder)
        assertEquals(WeatherAlertEvaluation.Decided(WeatherHazard.RAIN_EXPECTED), result)
    }

    @Test fun `exactly the heavy threshold is HEAVY_RAIN`() {
        val result = WeatherAlertPolicy.evaluate(WeatherCategory.RAIN, WeatherAlertPolicy.HEAVY_RAIN_MILLIMETERS)
        assertEquals(WeatherAlertEvaluation.Decided(WeatherHazard.HEAVY_RAIN), result)
    }

    // --- stronger hazard supersedes a generic rain alert --------------------

    @Test fun `hazard severity ordering places THUNDERSTORM above HEAVY_RAIN above RAIN_EXPECTED above NO_ALERT`() {
        val ordered = listOf(WeatherHazard.NO_ALERT, WeatherHazard.RAIN_EXPECTED, WeatherHazard.HEAVY_RAIN, WeatherHazard.THUNDERSTORM)
        assertEquals(ordered, WeatherHazard.entries.sortedBy { it.ordinal })
    }

    // --- policy version -------------------------------------------------------

    @Test fun `policy version is a fixed, non-zero constant`() {
        assertEquals(1, WeatherAlertPolicy.POLICY_VERSION)
    }

    // --- target date computation, local calendar arithmetic only ------------

    @Test fun `target date is always the very next local calendar day`() {
        assertEquals(LocalDate.of(2026, 9, 11), WeatherAlertPolicy.targetDateFor(LocalDate.of(2026, 9, 10)))
    }

    @Test fun `target date crosses a month boundary correctly`() {
        assertEquals(LocalDate.of(2026, 10, 1), WeatherAlertPolicy.targetDateFor(LocalDate.of(2026, 9, 30)))
    }

    @Test fun `target date crosses a year boundary correctly`() {
        assertEquals(LocalDate.of(2027, 1, 1), WeatherAlertPolicy.targetDateFor(LocalDate.of(2026, 12, 31)))
    }
}
