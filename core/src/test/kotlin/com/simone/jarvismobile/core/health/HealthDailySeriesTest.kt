package com.simone.jarvismobile.core.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Proves the date/timezone bucketing behind the "BPM non si aggiorna, manca
 * ieri, manca il sonno della notte appena trascorsa" bug report is either
 * genuinely correct (so the remaining issue is Health Connect/OEM sync, not
 * this arithmetic) or, if any case here fails, a real bug caught with a
 * reproducible test instead of another blind guess. All times are
 * constructed via [ZonedDateTime] against a named zone (mostly
 * Europe/Rome, the target device's zone) and converted to [Instant] — never
 * a hand-computed UTC offset — so the test itself can't hide a sign error.
 */
class HealthDailySeriesTest {

    private val rome = ZoneId.of("Europe/Rome")

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId = rome): Instant =
        ZonedDateTime.of(date, time, zone).toInstant()

    // --- sleepDuration --------------------------------------------------------

    @Test
    fun `sonno 23-30 to 07-30, no stage detail, counts the full crossed-midnight span`() {
        val start = LocalDate.of(2026, 8, 29)
        val session = SleepSessionSpan(
            startTime = at(start, LocalTime.of(23, 30)),
            endTime = at(start.plusDays(1), LocalTime.of(7, 30)),
        )
        assertEquals(Duration.ofHours(8), HealthDailySeries.sleepDuration(session))
    }

    @Test
    fun `sonno 01-00 to 08-00, same calendar day, no midnight crossing`() {
        val day = LocalDate.of(2026, 8, 30)
        val session = SleepSessionSpan(startTime = at(day, LocalTime.of(1, 0)), endTime = at(day, LocalTime.of(8, 0)))
        assertEquals(Duration.ofHours(7), HealthDailySeries.sleepDuration(session))
    }

    @Test
    fun `awake stages inside the session are excluded from the counted duration`() {
        val start = LocalDate.of(2026, 8, 29)
        val fullSpan = SleepSessionSpan(
            startTime = at(start, LocalTime.of(23, 30)),
            endTime = at(start.plusDays(1), LocalTime.of(7, 30)),
            stages = listOf(
                SleepStageSpan(awake = false, at(start, LocalTime.of(23, 30)), at(start.plusDays(1), LocalTime.of(2, 0))),
                SleepStageSpan(awake = true, at(start.plusDays(1), LocalTime.of(2, 0)), at(start.plusDays(1), LocalTime.of(2, 20))),
                SleepStageSpan(awake = false, at(start.plusDays(1), LocalTime.of(2, 20)), at(start.plusDays(1), LocalTime.of(7, 30))),
            ),
        )
        // 8h total span minus the 20-minute awake stage = 7h40m real sleep.
        assertEquals(Duration.ofHours(7).plusMinutes(40), HealthDailySeries.sleepDuration(fullSpan))
    }

    @Test
    fun `DST spring-forward night (clocks skip an hour) still yields the true elapsed duration`() {
        // Europe/Rome 2026: clocks jump 02:00 to 03:00 on 2026-03-29.
        val start = LocalDate.of(2026, 3, 28)
        val session = SleepSessionSpan(
            startTime = at(start, LocalTime.of(23, 30)),
            endTime = at(start.plusDays(1), LocalTime.of(7, 30)),
        )
        // Wall clock reads 8h, but one hour never happened locally -> 7h real.
        assertEquals(Duration.ofHours(7), HealthDailySeries.sleepDuration(session))
    }

    @Test
    fun `DST fall-back night (clocks repeat an hour) still yields the true elapsed duration`() {
        // Europe/Rome 2026: clocks fall back 03:00 to 02:00 on 2026-10-25.
        val start = LocalDate.of(2026, 10, 24)
        val session = SleepSessionSpan(
            startTime = at(start, LocalTime.of(23, 30)),
            endTime = at(start.plusDays(1), LocalTime.of(7, 30)),
        )
        // Wall clock reads 8h, but one hour happens twice locally -> 9h real.
        assertEquals(Duration.ofHours(9), HealthDailySeries.sleepDuration(session))
    }

    // --- dailySeries: sleep bucketing (wake-date attribution) ------------------

    @Test
    fun `a session crossing midnight is attributed to the wake day, not the day it started`() {
        val fellAsleep = LocalDate.of(2026, 8, 29)
        val wokeUp = fellAsleep.plusDays(1)
        val session = SleepSessionSpan(at(fellAsleep, LocalTime.of(23, 30)), at(wokeUp, LocalTime.of(7, 30)))
        val daily = HealthDailySeries.dailySeries(emptyList(), listOf(session), rome, today = wokeUp, windowDays = 7)

        assertEquals(8.0, daily.first { it.date == wokeUp }.sleepHours!!)
        assertNull(daily.first { it.date == fellAsleep }.sleepHours)
    }

    @Test
    fun `a session ending today appears in the daily list (not just yesterday's slot)`() {
        val today = LocalDate.of(2026, 8, 30)
        val session = SleepSessionSpan(at(today, LocalTime.of(1, 0)), at(today, LocalTime.of(8, 0)))
        val daily = HealthDailySeries.dailySeries(emptyList(), listOf(session), rome, today, windowDays = 7)

        val todayEntry = daily.last()
        assertEquals(today, todayEntry.date)
        assertEquals(7.0, todayEntry.sleepHours!!)
    }

    @Test
    fun `a session ending yesterday appears in yesterday's slot`() {
        val today = LocalDate.of(2026, 8, 30)
        val yesterday = today.minusDays(1)
        val session = SleepSessionSpan(at(yesterday, LocalTime.of(0, 30)), at(yesterday, LocalTime.of(7, 0)))
        val daily = HealthDailySeries.dailySeries(emptyList(), listOf(session), rome, today, windowDays = 7)

        val yEntry = daily.first { it.date == yesterday }
        assertEquals(6.5, yEntry.sleepHours!!)
    }

    // --- dailySeries: BPM bucketing + missing-day handling ---------------------

    @Test
    fun `heart rate is bucketed by the sample's own local date`() {
        val today = LocalDate.of(2026, 8, 30)
        val yesterday = today.minusDays(1)
        val samples = listOf(
            HeartRateSample(at(yesterday, LocalTime.of(6, 0)), 52),
            HeartRateSample(at(yesterday, LocalTime.of(6, 5)), 54),
            HeartRateSample(at(today, LocalTime.of(6, 0)), 58),
        )
        val daily = HealthDailySeries.dailySeries(samples, emptyList(), rome, today, windowDays = 7)

        assertEquals(53L, daily.first { it.date == yesterday }.heartRateBpm)
        assertEquals(58L, daily.last().heartRateBpm)
    }

    @Test
    fun `seven-day window with one dataless day - missing day is null, not zero, and excluded from the average`() {
        val today = LocalDate.of(2026, 8, 30)
        val missing = today.minusDays(3)
        val samples = (1..7).filter { LocalDate.of(2026, 8, 30).minusDays(it.toLong()) != missing }
            .map { daysAgo -> HeartRateSample(at(today.minusDays(daysAgo.toLong()), LocalTime.of(6, 0)), 60L) }
        val daily = HealthDailySeries.dailySeries(samples, emptyList(), rome, today, windowDays = 7)

        assertNull(daily.first { it.date == missing }.heartRateBpm)
        val averages = HealthDailySeries.computeAverages(daily, today)
        // 6 real days at 60 bpm each -> average is still exactly 60, proving
        // the missing day was excluded rather than silently averaged as 0.
        assertEquals(60L, averages.avgHeartRateBpm)
    }

    @Test
    fun `today is present in the daily list but excluded from the weekly average`() {
        val today = LocalDate.of(2026, 8, 30)
        val samples = listOf(
            HeartRateSample(at(today.minusDays(1), LocalTime.of(6, 0)), 50L),
            HeartRateSample(at(today, LocalTime.of(6, 0)), 90L),
        )
        val daily = HealthDailySeries.dailySeries(samples, emptyList(), rome, today, windowDays = 7)
        val averages = HealthDailySeries.computeAverages(daily, today)

        assertEquals(90L, daily.last().heartRateBpm) // shown in the list/dialog/sparkline...
        assertEquals(50L, averages.avgHeartRateBpm) // ...but never mixed into the average.
    }

    @Test
    fun `dailySeries has windowDays plus one entries, oldest first, today last`() {
        val today = LocalDate.of(2026, 8, 30)
        val daily = HealthDailySeries.dailySeries(emptyList(), emptyList(), rome, today, windowDays = 7)

        assertEquals(8, daily.size)
        assertEquals(today.minusDays(7), daily.first().date)
        assertEquals(today, daily.last().date)
    }

    // --- staleness: a fresh call always reflects the latest input, never a cached old value ---

    @Test
    fun `a newer sample replaces the previous result on the next call - no memoization, no stale value`() {
        val today = LocalDate.of(2026, 8, 30)
        val zone = rome
        val firstCall = HealthDailySeries.dailySeries(
            listOf(HeartRateSample(at(today, LocalTime.of(6, 0)), 55L)),
            emptyList(),
            zone,
            today,
        )
        assertEquals(55L, firstCall.last().heartRateBpm)

        // A brand-new, independent call with an updated sample set - simulates
        // Health Connect having synced a fresh reading since the last refresh.
        val secondCall = HealthDailySeries.dailySeries(
            listOf(
                HeartRateSample(at(today, LocalTime.of(6, 0)), 55L),
                HeartRateSample(at(today, LocalTime.of(9, 0)), 71L),
            ),
            emptyList(),
            zone,
            today,
        )
        assertEquals(63L, secondCall.last().heartRateBpm) // average of 55 and 71, rounded
    }

    // --- queryRange --------------------------------------------------------

    @Test
    fun `queryRange spans local midnight windowDays ago through now, never a fixed calendar week`() {
        val today = LocalDate.of(2026, 8, 30)
        val now = at(today, LocalTime.of(14, 30))
        val range = HealthDailySeries.queryRange(today, rome, now, windowDays = 7)

        assertEquals(at(today.minusDays(7), LocalTime.MIDNIGHT), range.start)
        assertEquals(now, range.endInclusive)
    }

    @Test
    fun `a reading from earlier today falls inside queryRange even though today is not yet over`() {
        val today = LocalDate.of(2026, 8, 30)
        val now = at(today, LocalTime.of(9, 0))
        val range = HealthDailySeries.queryRange(today, rome, now, windowDays = 7)
        val earlierToday = at(today, LocalTime.of(6, 0))

        assertTrue(earlierToday in range)
    }
}
