package com.simone.jarvismobile.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 7 §5/§10-test-10/11
 * (JARVIS-15). Pins that a qualitative classification (a) is deterministic
 * and versioned, (b) always carries the exact number that produced it, and
 * (c) never mutates the underlying temperature.
 */
class WeatherTemperaturePolicyTest {

    @Test
    fun `below 5 degrees is COLD`() {
        assertEquals(ThermalComfortBand.COLD, WeatherTemperaturePolicy.classify(-2.0).band)
        assertEquals(ThermalComfortBand.COLD, WeatherTemperaturePolicy.classify(4.9).band)
    }

    @Test
    fun `5 to under 15 degrees is COOL`() {
        assertEquals(ThermalComfortBand.COOL, WeatherTemperaturePolicy.classify(5.0).band)
        assertEquals(ThermalComfortBand.COOL, WeatherTemperaturePolicy.classify(14.9).band)
    }

    @Test
    fun `15 to under 22 degrees is MILD`() {
        assertEquals(ThermalComfortBand.MILD, WeatherTemperaturePolicy.classify(15.0).band)
        assertEquals(ThermalComfortBand.MILD, WeatherTemperaturePolicy.classify(21.9).band)
    }

    @Test
    fun `22 to under 30 degrees is WARM`() {
        assertEquals(ThermalComfortBand.WARM, WeatherTemperaturePolicy.classify(22.0).band)
        assertEquals(ThermalComfortBand.WARM, WeatherTemperaturePolicy.classify(29.9).band)
    }

    @Test
    fun `30 degrees and above is HOT`() {
        assertEquals(ThermalComfortBand.HOT, WeatherTemperaturePolicy.classify(30.0).band)
        assertEquals(ThermalComfortBand.HOT, WeatherTemperaturePolicy.classify(41.0).band)
    }

    @Test
    fun `the classification always carries the exact supporting temperature verbatim, never rounded or altered`() {
        val result = WeatherTemperaturePolicy.classify(28.6543)
        assertEquals(28.6543, result.supportingTempC)
    }

    @Test
    fun `the result always carries the current policy version`() {
        val result = WeatherTemperaturePolicy.classify(20.0)
        assertEquals(WeatherTemperaturePolicy.POLICY_VERSION, result.policyVersion)
    }

    @Test
    fun `classify never mutates its input - calling it twice with the same value yields the same band`() {
        val temp = 31.2
        val first = WeatherTemperaturePolicy.classify(temp)
        val second = WeatherTemperaturePolicy.classify(temp)
        assertEquals(first, second)
        assertEquals(temp, first.supportingTempC)
        assertEquals(temp, second.supportingTempC)
    }

    @Test
    fun `italianLabel is defined for every band`() {
        ThermalComfortBand.entries.forEach { band ->
            assert(band.italianLabel.isNotBlank())
        }
    }
}
