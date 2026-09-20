package com.simone.jarvismobile.core.weather.replay

import kotlinx.serialization.Serializable

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE E §20. The OBSERVED OUTCOME CONTRACT — defined and
 * versioned BEFORE any scoring happens (§20's explicit requirement: "do
 * not move the goalposts after seeing results"), richer than
 * [ObservedOutcome]'s plain binary so a qualification protocol can record
 * what actually happened without collapsing "it snowed" and "it stayed
 * dry" into the same label at collection time.
 *
 * This is deliberately a SEPARATE type from [ObservedOutcome], not a
 * replacement — [ObservedOutcome] stays exactly what
 * [WeatherAlertReplay.aggregate]'s TP/FP/TN/FN counting has always used
 * and remains untouched; [WeatherObservedOutcome] is the richer label a
 * human/authoritative-source records during shadow capture, converted to
 * [ObservedOutcome] only via the explicit, tested, versioned
 * [toAggregationTruth] — never silently, never inline at call sites.
 */
const val OBSERVED_OUTCOME_CONTRACT_VERSION: Int = 1

@Serializable
enum class WeatherObservedOutcome {
    /** Meaningful liquid rain actually fell — the product's rain-alert product threshold, not mere trace precipitation (§20/§21: "do not call trace precipitation automatically equivalent to the proactive product threshold"). */
    RAIN_OBSERVED,

    /** No meaningfully wet weather occurred — dry, or precipitation below the product's own qualifying threshold. */
    NO_MEANINGFUL_RAIN_OBSERVED,

    /** A real thunderstorm occurred (the storm hazard tier, distinct from ordinary rain). */
    STORM_OBSERVED,

    /** Snow or mixed/icy precipitation occurred — [WeatherAlertPolicyV2][com.simone.jarvismobile.core.weather.WeatherAlertPolicyV2] deliberately never claims a rain alert for this (§9/§17), so this is its own outcome, never folded into [RAIN_OBSERVED]. */
    SNOW_MIXED,

    /** No authoritative observation is available for this target day — MUST be excluded from scoring, never guessed, never silently counted as a true negative (§21: "do not hide UNKNOWN cases by counting them as TN"). */
    OBSERVATION_UNKNOWN,
    ;

    /**
     * § §20/§21 — the ONE place [WeatherObservedOutcome]'s five labels are
     * turned into the binary ground truth [WeatherAlertReplay.aggregate]
     * counts against. `null` means "exclude from TP/FP/TN/FN entirely",
     * used only for [OBSERVATION_UNKNOWN] — every other label maps to an
     * explicit, documented boolean, never inferred elsewhere.
     *
     * [SNOW_MIXED] maps to `false` (no rain-hazard truth), NOT excluded
     * and NOT `true`: the product's own policy deliberately never alerts
     * for snow/mixed precipitation (§9/§17), so a day where only snow fell
     * is a genuine true negative for the RAIN alert being scored here —
     * treating it as a positive would be exactly the "trace precipitation
     * equivalent to the product threshold" conflation §20 forbids.
     */
    fun toAggregationTruth(): ObservedOutcome? = when (this) {
        RAIN_OBSERVED, STORM_OBSERVED -> ObservedOutcome.HAZARD_OCCURRED
        NO_MEANINGFUL_RAIN_OBSERVED, SNOW_MIXED -> ObservedOutcome.NO_HAZARD_OCCURRED
        OBSERVATION_UNKNOWN -> null
    }
}
