package com.simone.jarvismobile.core.weather

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D §13 — why a [ForecastFacts] set is or is not eligible for a
 * PROACTIVE evening alert decision. Deliberately its own, STRICTER policy
 * than [WeatherFreshnessPolicy] (6h, used for ordinary UI display) — §13
 * explicitly requires the proactive alert to have its own eligibility rules
 * without silently changing the UI's existing valid freshness requirement.
 */
enum class ForecastEligibilityReason {
    ELIGIBLE,
    MISSING_FACTS,
    DATE_MISMATCH,
    LOCATION_MISMATCH,
    STALE,
    FUTURE_TIMESTAMP,
}

object WeatherAlertFreshnessPolicyV2 {
    /** § §13 — "no older than 3 hours". */
    const val MAX_AGE_HOURS = 3L

    /** § §7 — a small, explicit clock-skew tolerance; a timestamp further in the future than this is treated as invalid rather than trusted. */
    val CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5)

    /**
     * [facts] `null` means "no forecast was fetched at all" — always
     * [ForecastEligibilityReason.MISSING_FACTS], never treated the same as a
     * genuinely stale one. A [facts] whose [ForecastFacts.targetDate] or
     * [ForecastFacts.locationRevision] no longer matches the CURRENT
     * expected target/location is rejected even if it is otherwise fresh
     * (§11/§12 — location A must never satisfy location B).
     */
    fun evaluate(
        facts: ForecastFacts?,
        expectedTargetDate: LocalDate,
        currentLocationRevision: String,
        now: Instant,
    ): ForecastEligibilityReason {
        if (facts == null) return ForecastEligibilityReason.MISSING_FACTS
        if (facts.targetDate != expectedTargetDate) return ForecastEligibilityReason.DATE_MISMATCH
        if (facts.locationRevision != currentLocationRevision) return ForecastEligibilityReason.LOCATION_MISMATCH
        val age = Duration.between(facts.fetchedAt, now)
        if (age.isNegative && age.abs() > CLOCK_SKEW_TOLERANCE) return ForecastEligibilityReason.FUTURE_TIMESTAMP
        if (age.toMillis() > Duration.ofHours(MAX_AGE_HOURS).toMillis()) return ForecastEligibilityReason.STALE
        return ForecastEligibilityReason.ELIGIBLE
    }
}
