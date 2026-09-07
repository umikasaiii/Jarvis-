package com.simone.jarvismobile.core.health

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 5 (Health Range + Coverage +
 * Freshness Foundation). Pins the exact distinction `nightResult()`/
 * `weeklySleepResult()` used to collapse: a date/range genuinely never
 * queried ([HealthDayCoverage.NotCovered] / zero [HealthRangeCoverage.coveredDays])
 * must never be confused with a date/range that WAS queried and simply holds
 * no record ([HealthDayCoverage.Covered] with a null field / zero
 * [HealthRangeCoverage.daysWithData] but [HealthRangeCoverage.hasAnyCoverage]).
 */
class HealthCoverageTest {

    private val day = LocalDate.of(2026, 9, 5)

    // --- resolveDay -----------------------------------------------------------

    @Test
    fun `a date present in daily - even with null fields - is Covered, never NotCovered`() {
        val daily = listOf(DailyHealthReading(day, heartRateBpm = null, sleepHours = null))
        val coverage = HealthCoverage.resolveDay(daily, day)
        assertTrue(coverage is HealthDayCoverage.Covered)
        assertEquals(null, (coverage as HealthDayCoverage.Covered).reading.sleepHours)
    }

    @Test
    fun `a date absent from daily is NotCovered, never inferred as an empty covered day`() {
        val daily = listOf(DailyHealthReading(day.minusDays(1), 55L, 7.0))
        val coverage = HealthCoverage.resolveDay(daily, day)
        assertEquals(HealthDayCoverage.NotCovered, coverage)
    }

    @Test
    fun `a date present with real values is Covered and carries the real reading`() {
        val daily = listOf(DailyHealthReading(day, heartRateBpm = 58L, sleepHours = 7.5))
        val coverage = HealthCoverage.resolveDay(daily, day)
        assertTrue(coverage is HealthDayCoverage.Covered)
        assertEquals(58L, (coverage as HealthDayCoverage.Covered).reading.heartRateBpm)
        assertEquals(7.5, coverage.reading.sleepHours)
    }

    @Test
    fun `an empty daily list means every date is NotCovered`() {
        assertEquals(HealthDayCoverage.NotCovered, HealthCoverage.resolveDay(emptyList(), day))
    }

    // --- resolveRange -----------------------------------------------------------

    @Test
    fun `a fully windowed range with real data on every day is fully covered, has data, SUCCESS_DATA-shaped`() {
        val windowed = (0..7).map { DailyHealthReading(day.minusDays(it.toLong()), 55L, 7.0) }
        val coverage = HealthCoverage.resolveRange(windowed, requestedDays = 8) { it.sleepHours != null }
        assertTrue(coverage.isFullyCovered)
        assertTrue(coverage.hasAnyCoverage)
        assertTrue(coverage.hasAnyData)
        assertEquals(8, coverage.coveredDays)
        assertEquals(8, coverage.daysWithData)
    }

    @Test
    fun `covered range with zero real records is SUCCESS_EMPTY-shaped - hasAnyCoverage but not hasAnyData`() {
        val windowed = (0..7).map { DailyHealthReading(day.minusDays(it.toLong()), null, null) }
        val coverage = HealthCoverage.resolveRange(windowed, requestedDays = 8) { it.sleepHours != null }
        assertTrue(coverage.isFullyCovered)
        assertTrue(coverage.hasAnyCoverage)
        assertFalse(coverage.hasAnyData)
        assertEquals(0, coverage.daysWithData)
    }

    @Test
    fun `an empty windowed list is DATA_UNAVAILABLE-shaped - no coverage at all, never SUCCESS_EMPTY`() {
        val coverage = HealthCoverage.resolveRange(emptyList(), requestedDays = 8) { it.sleepHours != null }
        assertFalse(coverage.hasAnyCoverage)
        assertFalse(coverage.hasAnyData)
        assertFalse(coverage.isFullyCovered)
        assertEquals(0, coverage.coveredDays)
    }

    @Test
    fun `partial coverage - some days never queried - is distinct from full coverage, never presented as full-range certainty`() {
        // Only 3 of the 8 requested days were ever queried (e.g. a stale cache).
        val windowed = (0..2).map { DailyHealthReading(day.minusDays(it.toLong()), 55L, 7.0) }
        val coverage = HealthCoverage.resolveRange(windowed, requestedDays = 8) { it.sleepHours != null }
        assertFalse(coverage.isFullyCovered)
        assertTrue(coverage.hasAnyCoverage)
        assertTrue(coverage.hasAnyData)
        assertEquals(3, coverage.coveredDays)
        assertEquals(3, coverage.daysWithData)
    }

    @Test
    fun `daysWithData only counts covered days that actually satisfy hasData, distinct metric selectors never leak into each other`() {
        val windowed = listOf(
            DailyHealthReading(day, heartRateBpm = 58L, sleepHours = null),
            DailyHealthReading(day.minusDays(1), heartRateBpm = null, sleepHours = 7.0),
        )
        val sleepCoverage = HealthCoverage.resolveRange(windowed, requestedDays = 2) { it.sleepHours != null }
        val bpmCoverage = HealthCoverage.resolveRange(windowed, requestedDays = 2) { it.heartRateBpm != null }
        assertEquals(1, sleepCoverage.daysWithData)
        assertEquals(1, bpmCoverage.daysWithData)
        assertEquals(2, sleepCoverage.coveredDays)
        assertEquals(2, bpmCoverage.coveredDays)
    }

    @Test
    fun `coveredDays can never exceed requestedDays by construction`() {
        // Ten queried days but only 8 were actually requested (e.g. windowed
        // was accidentally passed a wider list than the requested range).
        val windowed = (0..9).map { DailyHealthReading(day.minusDays(it.toLong()), 55L, 7.0) }
        val coverage = HealthCoverage.resolveRange(windowed, requestedDays = 8)  { it.sleepHours != null }
        assertEquals(8, coverage.coveredDays)
        assertTrue(coverage.isFullyCovered)
    }

    @Test
    fun `HealthRangeCoverage rejects an internally inconsistent construction rather than silently accepting it`() {
        assertFailsWith<IllegalArgumentException> { HealthRangeCoverage(requestedDays = 7, coveredDays = 8, daysWithData = 0) }
        assertFailsWith<IllegalArgumentException> { HealthRangeCoverage(requestedDays = 7, coveredDays = 5, daysWithData = 6) }
    }
}
