package com.simone.jarvismobile.core.weather

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherAlertFreshnessPolicyV2Test {

    private val target = LocalDate.of(2026, 9, 20)
    private val location = "coord:41.90,12.50"

    private fun facts(fetchedAt: Instant, date: LocalDate = target, locationRevision: String = location) = ForecastFacts(
        targetDate = date,
        providerTimezone = "Europe/Rome",
        locationRevision = locationRevision,
        fetchedAt = fetchedAt,
        rawWeatherCode = 61,
        category = WeatherCategory.RAIN,
        precipitationSumMm = 2.0,
        rainSumMm = 2.0,
        showersSumMm = null,
        snowfallSumCm = null,
        precipitationProbabilityMaxPercent = 80.0,
        precipitationHours = 3.0,
    )

    // --- W09: cached freshness result ------------------------------------------

    @Test fun missingFactsIsNeverEligible() {
        val now = Instant.parse("2026-09-19T18:00:00Z")
        assertEquals(ForecastEligibilityReason.MISSING_FACTS, WeatherAlertFreshnessPolicyV2.evaluate(null, target, location, now))
    }

    @Test fun freshMatchingFactsAreEligible() {
        val fetchedAt = Instant.parse("2026-09-19T18:00:00Z")
        val now = fetchedAt.plus(Duration.ofHours(1))
        assertEquals(ForecastEligibilityReason.ELIGIBLE, WeatherAlertFreshnessPolicyV2.evaluate(facts(fetchedAt), target, location, now))
    }

    @Test fun exactlyThreeHoursOldIsStillEligible_justOverIsStale() {
        val fetchedAt = Instant.parse("2026-09-19T18:00:00Z")
        val atThreeHours = fetchedAt.plus(Duration.ofHours(3))
        assertEquals(ForecastEligibilityReason.ELIGIBLE, WeatherAlertFreshnessPolicyV2.evaluate(facts(fetchedAt), target, location, atThreeHours))
        val justOver = fetchedAt.plus(Duration.ofHours(3)).plusSeconds(1)
        assertEquals(ForecastEligibilityReason.STALE, WeatherAlertFreshnessPolicyV2.evaluate(facts(fetchedAt), target, location, justOver))
    }

    @Test fun expiredMismatchedCacheDoesNotAlert() {
        val fetchedAt = Instant.parse("2026-09-19T18:00:00Z")
        val muchLater = fetchedAt.plus(Duration.ofHours(30))
        assertEquals(ForecastEligibilityReason.STALE, WeatherAlertFreshnessPolicyV2.evaluate(facts(fetchedAt), target, location, muchLater))
    }

    // --- W06: date/location independence, midnight/DST-safe (pure part) --------

    @Test fun dateMismatchIsRejectedEvenIfFresh() {
        val fetchedAt = Instant.parse("2026-09-19T18:00:00Z")
        val now = fetchedAt.plus(Duration.ofMinutes(1))
        val wrongDate = facts(fetchedAt, date = target.plusDays(1))
        assertEquals(ForecastEligibilityReason.DATE_MISMATCH, WeatherAlertFreshnessPolicyV2.evaluate(wrongDate, target, location, now))
    }

    @Test fun locationMismatchIsRejectedEvenIfFresh_locationACannotSatisfyLocationB() {
        val fetchedAt = Instant.parse("2026-09-19T18:00:00Z")
        val now = fetchedAt.plus(Duration.ofMinutes(1))
        val otherLocation = facts(fetchedAt, locationRevision = "coord:45.00,9.00")
        assertEquals(ForecastEligibilityReason.LOCATION_MISMATCH, WeatherAlertFreshnessPolicyV2.evaluate(otherLocation, target, location, now))
    }

    @Test fun futureTimestampBeyondClockSkewToleranceIsRejected() {
        val fetchedAt = Instant.parse("2026-09-19T18:00:00Z")
        val now = fetchedAt.minus(Duration.ofMinutes(10)) // fetchedAt is 10 minutes in the "future" relative to now
        assertEquals(ForecastEligibilityReason.FUTURE_TIMESTAMP, WeatherAlertFreshnessPolicyV2.evaluate(facts(fetchedAt), target, location, now))
    }

    @Test fun futureTimestampWithinClockSkewToleranceIsAccepted() {
        val fetchedAt = Instant.parse("2026-09-19T18:00:00Z")
        val now = fetchedAt.minus(Duration.ofMinutes(2))
        assertEquals(ForecastEligibilityReason.ELIGIBLE, WeatherAlertFreshnessPolicyV2.evaluate(facts(fetchedAt), target, location, now))
    }

    @Test fun midnightBoundaryDoesNotAffectAgeArithmetic_instantBased() {
        // fetchedAt just before local midnight, now just after — real elapsed
        // time is what matters, computed on Instant, never on a LocalDate diff.
        val fetchedAt = Instant.parse("2026-09-19T23:50:00Z")
        val now = Instant.parse("2026-09-20T00:10:00Z")
        assertEquals(ForecastEligibilityReason.ELIGIBLE, WeatherAlertFreshnessPolicyV2.evaluate(facts(fetchedAt), target, location, now))
    }
}
