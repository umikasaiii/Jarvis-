package com.simone.jarvismobile.core.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. Local wall-clock
 * arithmetic only — both timestamps compared always come from the same
 * device clock, so there is no UTC/zone conversion to get wrong here; these
 * tests instead exercise the actual boundary behaviour (never-fetched,
 * exactly-at-threshold, and a comparison that crosses local midnight).
 */
class WeatherFreshnessPolicyTest {

    @Test fun `never fetched is always stale`() {
        assertTrue(WeatherFreshnessPolicy.isStale(null, LocalDateTime.of(2026, 9, 10, 20, 0)))
    }

    @Test fun `well within the threshold is not stale`() {
        val updatedAt = LocalDateTime.of(2026, 9, 10, 19, 0)
        val now = updatedAt.plusHours(1)
        assertFalse(WeatherFreshnessPolicy.isStale(updatedAt, now))
    }

    @Test fun `exactly at the staleness threshold is stale`() {
        val updatedAt = LocalDateTime.of(2026, 9, 10, 14, 0)
        val now = updatedAt.plusHours(WeatherFreshnessPolicy.STALE_AFTER_HOURS)
        assertTrue(WeatherFreshnessPolicy.isStale(updatedAt, now))
    }

    @Test fun `one minute before the threshold is not stale`() {
        val updatedAt = LocalDateTime.of(2026, 9, 10, 14, 0)
        val now = updatedAt.plusHours(WeatherFreshnessPolicy.STALE_AFTER_HOURS).minusMinutes(1)
        assertFalse(WeatherFreshnessPolicy.isStale(updatedAt, now))
    }

    @Test fun `a comparison crossing local midnight is still correct arithmetic`() {
        // Fetched at 23:50 yesterday, checked at 00:10 today — 20 real minutes
        // apart despite the calendar date changing, never mistaken for a
        // near-24h gap by naive date-only comparison.
        val updatedAt = LocalDateTime.of(LocalDate.of(2026, 9, 10), LocalTime.of(23, 50))
        val now = LocalDateTime.of(LocalDate.of(2026, 9, 11), LocalTime.of(0, 10))
        assertFalse(WeatherFreshnessPolicy.isStale(updatedAt, now))
    }

    @Test fun `a comparison crossing local midnight still detects genuine staleness`() {
        val updatedAt = LocalDateTime.of(LocalDate.of(2026, 9, 10), LocalTime.of(17, 0))
        val now = LocalDateTime.of(LocalDate.of(2026, 9, 10), LocalTime.of(23, 30))
        assertTrue(WeatherFreshnessPolicy.isStale(updatedAt, now))
    }
}
