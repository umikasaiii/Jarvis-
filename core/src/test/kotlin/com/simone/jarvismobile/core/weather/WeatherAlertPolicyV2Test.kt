package com.simone.jarvismobile.core.weather

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherAlertPolicyV2Test {

    private val target = LocalDate.of(2026, 9, 20)
    private val fetchedAt = Instant.parse("2026-09-19T18:00:00Z")

    private fun facts(
        code: Int?,
        precipSum: Double? = null,
        rainSum: Double? = null,
        showersSum: Double? = null,
        probability: Double? = null,
        hours: Double? = null,
    ) = ForecastFacts(
        targetDate = target,
        providerTimezone = "Europe/Rome",
        locationRevision = "coord:41.90,12.50",
        fetchedAt = fetchedAt,
        rawWeatherCode = code,
        category = WeatherCategory.fromWmoCode(code),
        precipitationSumMm = precipSum,
        rainSumMm = rainSum,
        showersSumMm = showersSum,
        snowfallSumCm = null,
        precipitationProbabilityMaxPercent = probability,
        precipitationHours = hours,
    )

    // --- W02: invalid numeric values -----------------------------------------

    @Test fun invalidProbability_negativeIsUnknownInvalid() {
        val d = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 2.0, probability = -1.0, hours = 3.0))
        assertEquals(WeatherHazard.NO_ALERT, d.hazard)
        assertEquals(WeatherDecisionReason.UNKNOWN_INVALID_DATA, d.reason)
    }

    @Test fun invalidProbability_above100IsUnknownInvalid() {
        val d = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 2.0, probability = 150.0, hours = 3.0))
        assertEquals(WeatherDecisionReason.UNKNOWN_INVALID_DATA, d.reason)
    }

    @Test fun invalidProbability_nanOrInfinityIsUnknownInvalid() {
        assertEquals(WeatherDecisionReason.UNKNOWN_INVALID_DATA, WeatherAlertPolicyV2.evaluate(facts(61, probability = Double.NaN)).reason)
        assertEquals(WeatherDecisionReason.UNKNOWN_INVALID_DATA, WeatherAlertPolicyV2.evaluate(facts(61, probability = Double.POSITIVE_INFINITY)).reason)
    }

    @Test fun invalidHours_negativeIsUnknownInvalid() {
        val d = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 2.0, probability = 80.0, hours = -1.0))
        assertEquals(WeatherDecisionReason.UNKNOWN_INVALID_DATA, d.reason)
    }

    // --- W03: exact policy boundaries -----------------------------------------

    @Test fun rainGate_probabilityBoundary_69failsExactly70qualifies() {
        val below = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 2.0, probability = 69.0, hours = 3.0))
        assertEquals(WeatherDecisionReason.BELOW_THRESHOLD, below.reason)
        val at = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 2.0, probability = 70.0, hours = 3.0))
        assertEquals(WeatherDecisionReason.RAIN_CANDIDATE_QUALIFIED, at.reason)
    }

    @Test fun rainGate_liquidBoundary_highPath() {
        // 0.99mm fails the high path (needs >=1.0) and fails the low path (needs >=0.5 AND hours>=2 -> would qualify actually)
        val justUnder = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 0.49, probability = 80.0, hours = 3.0))
        assertEquals(WeatherDecisionReason.BELOW_THRESHOLD, justUnder.reason)
        val atLow = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 0.5, probability = 80.0, hours = 2.0))
        assertEquals(WeatherDecisionReason.RAIN_CANDIDATE_QUALIFIED, atLow.reason)
        val atHigh = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 1.0, probability = 80.0, hours = 0.0))
        assertEquals(WeatherDecisionReason.RAIN_CANDIDATE_QUALIFIED, atHigh.reason)
        val justUnderHigh = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 0.99, probability = 80.0, hours = 0.0))
        assertEquals(WeatherDecisionReason.BELOW_THRESHOLD, justUnderHigh.reason)
    }

    @Test fun rainGate_lowPathNeedsBothLiquidAndHours() {
        // 0.5mm but only 1.9h -> fails low path, fails high path (needs 1.0mm) -> below threshold
        val d = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 0.5, probability = 80.0, hours = 1.9))
        assertEquals(WeatherDecisionReason.BELOW_THRESHOLD, d.reason)
    }

    @Test fun rainGate_missingLiquidAndPrecipitationSum_isUnknownMissingFacts() {
        val d = WeatherAlertPolicyV2.evaluate(facts(61, probability = 90.0, hours = 5.0))
        assertEquals(WeatherDecisionReason.UNKNOWN_MISSING_FACTS, d.reason)
    }

    @Test fun rainGate_missingProbability_isUnknownMissingFacts() {
        val d = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 5.0, hours = 5.0))
        assertEquals(WeatherDecisionReason.UNKNOWN_MISSING_FACTS, d.reason)
    }

    @Test fun rainGate_neverRendersAsDryOnBelowThreshold() {
        // Below threshold is a real, distinct outcome from "verified dry" — this
        // test simply pins that it is never collapsed into a NONE/no-precipitation reason.
        val d = WeatherAlertPolicyV2.evaluate(facts(61, rainSum = 0.1, probability = 30.0, hours = 0.0))
        assertEquals(WeatherHazard.NO_ALERT, d.hazard)
        assertEquals(WeatherDecisionReason.BELOW_THRESHOLD, d.reason)
    }

    @Test fun rainGate_heavyDailyAmountUpgradesTier_neverBypassesGate() {
        val heavy = WeatherAlertPolicyV2.evaluate(facts(61, precipSum = 12.0, rainSum = 12.0, probability = 80.0, hours = 5.0))
        assertEquals(WeatherHazard.HEAVY_RAIN, heavy.hazard)
        assertEquals(WeatherDecisionReason.RAIN_CANDIDATE_QUALIFIED, heavy.reason)
        // A heavy daily amount with a probability below the gate must NOT bypass it.
        val heavyButLowProbability = WeatherAlertPolicyV2.evaluate(facts(61, precipSum = 15.0, rainSum = 15.0, probability = 20.0, hours = 5.0))
        assertEquals(WeatherHazard.NO_ALERT, heavyButLowProbability.hazard)
        assertEquals(WeatherDecisionReason.BELOW_THRESHOLD, heavyButLowProbability.reason)
    }

    // --- W16: precipitation_sum fallback --------------------------------------

    @Test fun precipitationSumFallback_usedOnlyWhenRainShowersMissing_andReported() {
        val d = WeatherAlertPolicyV2.evaluate(facts(61, precipSum = 2.0, probability = 80.0, hours = 3.0))
        assertEquals(WeatherDecisionReason.RAIN_CANDIDATE_QUALIFIED, d.reason)
        assertEquals(true, d.precipitationFallbackUsed)
    }

    @Test fun precipitationSumFallback_notUsedWhenRainSumPresent() {
        val d = WeatherAlertPolicyV2.evaluate(facts(61, precipSum = 99.0, rainSum = 2.0, probability = 80.0, hours = 3.0))
        assertEquals(false, d.precipitationFallbackUsed)
    }

    // --- W05/W09: snow/mixed never become a rain alert ------------------------

    @Test fun snowOnly_highAccumulation_neverBecomesRainAlert() {
        // A big "precipitation_sum" on a pure snow code must still never alert as rain.
        val d = WeatherAlertPolicyV2.evaluate(facts(75, precipSum = 20.0, probability = 95.0, hours = 8.0))
        assertEquals(WeatherHazard.NO_ALERT, d.hazard)
        assertEquals(WeatherDecisionReason.SNOW_ONLY_NO_RAIN_ALERT, d.reason)
    }

    @Test fun mixedPrecipitation_isConservativeNeverAPlainRainClaim() {
        val d = WeatherAlertPolicyV2.evaluate(facts(66, precipSum = 5.0, probability = 90.0, hours = 5.0))
        assertEquals(WeatherHazard.NO_ALERT, d.hazard)
        assertEquals(WeatherDecisionReason.MIXED_PRECIPITATION_CONSERVATIVE, d.reason)
    }

    @Test fun missingLiquidComponentFields_onSnowCode_stillNeverRain() {
        val d = WeatherAlertPolicyV2.evaluate(facts(71))
        assertEquals(WeatherDecisionReason.SNOW_ONLY_NO_RAIN_ALERT, d.reason)
    }

    // --- W04: storm alignment --------------------------------------------------

    @Test fun stormCode_missingOrLowHourlyProbability_neverAutoAlertsFromDailyCodeAlone() {
        val dailyOnly = facts(95, precipSum = 5.0, probability = 20.0, hours = 1.0) // low daily probability too, below rain gate
        val d = WeatherAlertPolicyV2.evaluate(dailyOnly, hourlyEvidence = emptyList())
        assertEquals(WeatherHazard.NO_ALERT, d.hazard)
        assertEquals(WeatherDecisionReason.UNKNOWN_STORM_CONFIDENCE, d.reason)
    }

    @Test fun stormCode_highProbabilityOnANonStormHour_doesNotConfirmStorm() {
        val dailyOnly = facts(95, probability = 20.0, hours = 1.0)
        val nonStormHour = HourlyPrecipitationEvidence(target, hour = 14, rawWeatherCode = 61, precipitationProbabilityPercent = 95.0, rainMm = 5.0, showersMm = null, precipitationMm = null)
        val d = WeatherAlertPolicyV2.evaluate(dailyOnly, hourlyEvidence = listOf(nonStormHour))
        assertEquals(WeatherDecisionReason.UNKNOWN_STORM_CONFIDENCE, d.reason)
    }

    @Test fun stormCode_alignedConfirmingHour_qualifiesStorm() {
        val dailyOnly = facts(95, probability = 20.0, hours = 1.0)
        val stormHour = HourlyPrecipitationEvidence(target, hour = 16, rawWeatherCode = 95, precipitationProbabilityPercent = 75.0, rainMm = 0.3, showersMm = null, precipitationMm = null)
        val d = WeatherAlertPolicyV2.evaluate(dailyOnly, hourlyEvidence = listOf(stormHour))
        assertEquals(WeatherHazard.THUNDERSTORM, d.hazard)
        assertEquals(WeatherDecisionReason.STORM_CANDIDATE_QUALIFIED, d.reason)
        assertEquals(1, d.usedHourlyEvidenceCount)
    }

    @Test fun stormCode_unconfirmedButRainGateIndependentlyQualifies_stillRainCandidate() {
        // § §18 — a daily storm code that fails storm confirmation may still
        // qualify a generic rain candidate from its own daily facts.
        val dailyQualifiesAsRain = facts(95, rainSum = 3.0, probability = 90.0, hours = 5.0)
        val d = WeatherAlertPolicyV2.evaluate(dailyQualifiesAsRain, hourlyEvidence = emptyList())
        assertEquals(WeatherDecisionReason.RAIN_CANDIDATE_QUALIFIED, d.reason)
    }

    @Test fun stormCode_wrongDateHourlyEvidenceIsNeverUsed() {
        val dailyOnly = facts(95, probability = 20.0, hours = 1.0)
        val wrongDayStorm = HourlyPrecipitationEvidence(target.plusDays(1), hour = 16, rawWeatherCode = 95, precipitationProbabilityPercent = 95.0, rainMm = 1.0, showersMm = null, precipitationMm = null)
        val d = WeatherAlertPolicyV2.evaluate(dailyOnly, hourlyEvidence = listOf(wrongDayStorm))
        assertEquals(WeatherDecisionReason.UNKNOWN_STORM_CONFIDENCE, d.reason)
    }

    // --- determinism / no invented dry claim -----------------------------------

    @Test fun evaluateIsPureAndDeterministic() {
        val f = facts(61, rainSum = 2.0, probability = 80.0, hours = 3.0)
        val a = WeatherAlertPolicyV2.evaluate(f)
        val b = WeatherAlertPolicyV2.evaluate(f)
        assertEquals(a, b)
    }

    @Test fun clearDay_isNoAlert_withoutFabricatingADryClaimReasonBeyondNoAlert() {
        val d = WeatherAlertPolicyV2.evaluate(facts(0, rainSum = 0.0, probability = 5.0, hours = 0.0))
        assertEquals(WeatherHazard.NO_ALERT, d.hazard)
        assertEquals(WeatherDecisionReason.BELOW_THRESHOLD, d.reason)
    }
}
