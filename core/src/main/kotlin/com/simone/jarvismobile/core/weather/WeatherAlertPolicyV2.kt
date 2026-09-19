package com.simone.jarvismobile.core.weather

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D §22's own bounded reason vocabulary for a [WeatherAlertPolicyV2]
 * decision — every branch of [WeatherAlertPolicyV2.evaluate] returns exactly
 * one of these, persisted verbatim in a
 * [com.simone.jarvismobile.weather.receipt.ForecastDecisionReceiptEntity].
 * Never inferred from rendered text.
 */
enum class WeatherDecisionReason {
    RAIN_CANDIDATE_QUALIFIED,
    BELOW_THRESHOLD,
    STORM_CANDIDATE_QUALIFIED,
    UNKNOWN_STORM_CONFIDENCE,
    SNOW_ONLY_NO_RAIN_ALERT,
    MIXED_PRECIPITATION_CONSERVATIVE,
    UNKNOWN_MISSING_FACTS,
    UNKNOWN_INVALID_DATA,
}

/**
 * § §14 — configurable, versioned thresholds. Explicitly CANDIDATE PRODUCT
 * THRESHOLDS PENDING QUALIFICATION (§14/§42/Work Package E), not universal
 * meteorological truths — the exact values the Astra audit proposed as a
 * starting policy.
 */
data class WeatherAlertThresholdsV2(
    val configVersion: Int = 1,
    val rainProbabilityMinPercent: Double = 70.0,
    val rainLiquidMinMm: Double = 1.0,
    val rainLiquidLowMinMm: Double = 0.5,
    val rainMinHours: Double = 2.0,
    val heavyDailyAmountMm: Double = 10.0,
    val stormProbabilityMinPercent: Double = 70.0,
    val stormLiquidMinMm: Double = 0.2,
)

/**
 * The full decision — [hazard] reuses the existing (§ PASSAGGIO 14.2)
 * [WeatherHazard] enum so [com.simone.jarvismobile.core.proactive.ProactiveComposer.weatherAlert]/
 * [com.simone.jarvismobile.core.proactive.ProactiveOccurrenceKey] need no
 * changes — this is a new DECISION PATH into the same rendering/occurrence
 * contract, never a second notification owner (§2). [reason] is the exact
 * bounded justification, [usedHourlyEvidenceCount]/[precipitationFallbackUsed]
 * are receipt-worthy provenance (§16/§17).
 */
data class WeatherAlertDecisionV2(
    val hazard: WeatherHazard,
    val reason: WeatherDecisionReason,
    val usedHourlyEvidenceCount: Int = 0,
    val precipitationFallbackUsed: Boolean = false,
)

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D §14-§19. A NEW, versioned, pure, deterministic policy —
 * [WeatherAlertPolicy] (v1) is left entirely untouched for migration/replay
 * comparison (§34). No LLM, no natural-language parsing, no keyword
 * decision: every input is already-validated structured [ForecastFacts]/
 * [HourlyPrecipitationEvidence] (date/location/freshness validation happens
 * BEFORE this is called — see [WeatherAlertFreshnessPolicyV2] — so this
 * object only ever answers the METEOROLOGICAL question).
 *
 * §9 — consumes [ForecastFacts.rawWeatherCode] via [WmoPrecipitationKind],
 * never the lossy [WeatherCategory] projection: a snow-only or mixed-
 * precipitation forecast can never produce a rain alert here, closing the
 * exact defect [WeatherCategory.fromWmoCode] openly documents folding snow
 * into RAIN.
 */
object WeatherAlertPolicyV2 {
    const val POLICY_VERSION = 2

    fun evaluate(
        facts: ForecastFacts,
        hourlyEvidence: List<HourlyPrecipitationEvidence> = emptyList(),
        thresholds: WeatherAlertThresholdsV2 = WeatherAlertThresholdsV2(),
    ): WeatherAlertDecisionV2 {
        val probability = facts.precipitationProbabilityMaxPercent
        if (probability != null && (probability.isNaN() || probability.isInfinite() || probability < 0.0 || probability > 100.0)) {
            return WeatherAlertDecisionV2(WeatherHazard.NO_ALERT, WeatherDecisionReason.UNKNOWN_INVALID_DATA)
        }
        val hours = facts.precipitationHours
        if (hours != null && (hours.isNaN() || hours.isInfinite() || hours < 0.0)) {
            return WeatherAlertDecisionV2(WeatherHazard.NO_ALERT, WeatherDecisionReason.UNKNOWN_INVALID_DATA)
        }

        val kind = WmoPrecipitationKind.classify(facts.rawWeatherCode)
        when (kind) {
            PrecipitationKind.SNOW -> return WeatherAlertDecisionV2(WeatherHazard.NO_ALERT, WeatherDecisionReason.SNOW_ONLY_NO_RAIN_ALERT)
            PrecipitationKind.MIXED -> return WeatherAlertDecisionV2(WeatherHazard.NO_ALERT, WeatherDecisionReason.MIXED_PRECIPITATION_CONSERVATIVE)
            PrecipitationKind.UNKNOWN -> {
                val reason = if (facts.rawWeatherCode == null) WeatherDecisionReason.UNKNOWN_MISSING_FACTS else WeatherDecisionReason.UNKNOWN_INVALID_DATA
                return WeatherAlertDecisionV2(WeatherHazard.NO_ALERT, reason)
            }
            PrecipitationKind.NONE, PrecipitationKind.RAIN -> Unit // fall through below
        }

        // § §17/§18 — a daily storm code alone never alerts: require
        // date-aligned hourly evidence. If it doesn't confirm, the ordinary
        // rain gate may still independently qualify a generic candidate.
        if (facts.rawWeatherCode in WmoPrecipitationKind.STORM_CODES) {
            val aligned = hourlyEvidence.filter { it.date == facts.targetDate && it.rawWeatherCode in WmoPrecipitationKind.STORM_CODES }
            val confirmingHours = aligned.filter { h ->
                val p = h.precipitationProbabilityPercent
                val liquid = h.rainMm ?: h.showersMm ?: h.precipitationMm
                p != null && p >= thresholds.stormProbabilityMinPercent && liquid != null && liquid >= thresholds.stormLiquidMinMm
            }
            if (confirmingHours.isNotEmpty()) {
                return WeatherAlertDecisionV2(
                    hazard = WeatherHazard.THUNDERSTORM,
                    reason = WeatherDecisionReason.STORM_CANDIDATE_QUALIFIED,
                    usedHourlyEvidenceCount = confirmingHours.size,
                )
            }
            val rainGate = evaluateRainGate(facts, thresholds)
            return if (rainGate.hazard != WeatherHazard.NO_ALERT) {
                rainGate
            } else {
                WeatherAlertDecisionV2(WeatherHazard.NO_ALERT, WeatherDecisionReason.UNKNOWN_STORM_CONFIDENCE, usedHourlyEvidenceCount = aligned.size)
            }
        }

        return evaluateRainGate(facts, thresholds)
    }

    /**
     * § §15/§16/§19 — the meaningful-rain candidate gate: `P >= 70 AND (L >=
     * 1.0mm OR (L >= 0.5mm AND H >= 2h))`. `L = rain_sum + showers_sum`
     * (missing components treated as 0 when at least one is present);
     * falls back to `precipitation_sum` ONLY when neither component is
     * present, the code is precipitation-compatible (never for SNOW/MIXED —
     * already excluded by the caller), and the fallback is reported via
     * [WeatherAlertDecisionV2.precipitationFallbackUsed] (§16's explicit
     * receipt requirement). Below the gate is [WeatherDecisionReason
     * .BELOW_THRESHOLD] — never rendered as "it will not rain" (§15).
     */
    private fun evaluateRainGate(facts: ForecastFacts, thresholds: WeatherAlertThresholdsV2): WeatherAlertDecisionV2 {
        val probability = facts.precipitationProbabilityMaxPercent
            ?: return WeatherAlertDecisionV2(WeatherHazard.NO_ALERT, WeatherDecisionReason.UNKNOWN_MISSING_FACTS)

        val rain = facts.rainSumMm
        val showers = facts.showersSumMm
        var fallbackUsed = false
        val liquid: Double = when {
            rain != null || showers != null -> (rain ?: 0.0) + (showers ?: 0.0)
            facts.precipitationSumMm != null -> facts.precipitationSumMm.also { fallbackUsed = true }
            else -> null
        } ?: return WeatherAlertDecisionV2(WeatherHazard.NO_ALERT, WeatherDecisionReason.UNKNOWN_MISSING_FACTS)

        val hours = facts.precipitationHours ?: 0.0
        val qualifies = probability >= thresholds.rainProbabilityMinPercent &&
            (liquid >= thresholds.rainLiquidMinMm || (liquid >= thresholds.rainLiquidLowMinMm && hours >= thresholds.rainMinHours))

        if (!qualifies) {
            return WeatherAlertDecisionV2(WeatherHazard.NO_ALERT, WeatherDecisionReason.BELOW_THRESHOLD, precipitationFallbackUsed = fallbackUsed)
        }
        // § §19 — the daily total may add a heavier tier, never bypass the gate above.
        val dailyTotal = facts.precipitationSumMm ?: liquid
        val hazard = if (dailyTotal >= thresholds.heavyDailyAmountMm) WeatherHazard.HEAVY_RAIN else WeatherHazard.RAIN_EXPECTED
        return WeatherAlertDecisionV2(hazard, WeatherDecisionReason.RAIN_CANDIDATE_QUALIFIED, precipitationFallbackUsed = fallbackUsed)
    }
}
