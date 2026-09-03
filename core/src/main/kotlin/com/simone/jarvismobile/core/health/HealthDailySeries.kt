package com.simone.jarvismobile.core.health

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** One resting-heart-rate reading. [time] is an absolute instant — never a naive local time. */
data class HeartRateSample(val time: Instant, val bpm: Long)

/** One stage inside a sleep session; [awake] excludes it from the counted sleep duration. */
data class SleepStageSpan(val awake: Boolean, val start: Instant, val end: Instant)

/**
 * One sleep session. [stages] empty means the source (not every Health Connect
 * writer fills this in) never recorded stage detail — the whole
 * [startTime]..[endTime] span is then counted as sleep, since that's the only
 * data available.
 */
data class SleepSessionSpan(val startTime: Instant, val endTime: Instant, val stages: List<SleepStageSpan> = emptyList())

/** One calendar day's readings — either value can be legitimately absent (never a guess). */
data class DailyHealthReading(val date: LocalDate, val heartRateBpm: Long?, val sleepHours: Double?)

data class WeeklyHealthAverages(val avgHeartRateBpm: Long?, val avgSleepPerNight: Duration?)

/**
 * Pure date/timezone arithmetic behind the Ares "Sistema" BPM/sonno block —
 * extracted out of `app/`'s `HealthConnectManager` (which owns the actual
 * Health Connect reads) so it is unit-testable on a plain JVM: this
 * repository has no Robolectric/instrumented Android test infrastructure,
 * so any logic that stays behind an Android SDK type can only ever be
 * reviewed by eye, never proven by a running test. Everything here works
 * only on already-fetched, already-abstracted samples/sessions — no Health
 * Connect types, no Context.
 */
object HealthDailySeries {

    /**
     * Real elapsed sleep time: the session span minus any stages the source
     * marked as awake. Falls back to the raw start-end interval when no
     * stage detail exists (not every writer provides it) — the only data
     * available in that case, same convention Honor Health uses for a
     * session with no hypnogram.
     */
    fun sleepDuration(session: SleepSessionSpan): Duration {
        if (session.stages.isEmpty()) return Duration.between(session.startTime, session.endTime)
        return session.stages.asSequence()
            .filterNot { it.awake }
            .fold(Duration.ZERO) { acc, stage -> acc + Duration.between(stage.start, stage.end) }
    }

    /**
     * The instant range to query: local midnight [windowDays] ago through
     * [now] — a rolling window anchored to the device's own [zone], never a
     * fixed Mon-Sun calendar week and never UTC. [now] itself (not
     * yesterday's midnight) is the upper bound so an already-finished sleep
     * session or heart-rate reading from earlier *today* is never excluded
     * just because the day isn't over yet.
     */
    fun queryRange(today: LocalDate, zone: ZoneId, now: Instant, windowDays: Int = 7): ClosedRange<Instant> {
        val start = today.atStartOfDay(zone).toInstant().minus(windowDays.toLong(), ChronoUnit.DAYS)
        return start..now
    }

    /**
     * [windowDays] + 1 entries, oldest first, [today] last. A BPM sample is
     * bucketed by its own local date ([zone]-converted); a sleep session is
     * bucketed by the local date of its *end* (the wake-up), not its start —
     * so a session that begins the evening before still lands on the day the
     * user actually got up, matching how Honor Health (and most sleep
     * trackers) attribute a night's sleep. A day with no reading gets `null`
     * in that slot, never zero — zero is a real, different value (heart
     * stopped / no sleep at all), not "we have no data".
     */
    fun dailySeries(
        heartRateSamples: List<HeartRateSample>,
        sleepSessions: List<SleepSessionSpan>,
        zone: ZoneId,
        today: LocalDate,
        windowDays: Int = 7,
    ): List<DailyHealthReading> {
        val bpmByDate = heartRateSamples.groupBy({ it.time.atZone(zone).toLocalDate() }, { it.bpm })
        val sleepByDate = sleepSessions.groupBy({ it.endTime.atZone(zone).toLocalDate() }, { sleepDuration(it) })
        return (windowDays downTo 0).map { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            DailyHealthReading(
                date = date,
                heartRateBpm = bpmByDate[date]?.let { it.average().roundToLong() },
                sleepHours = sleepByDate[date]?.let { sessions -> sessions.sumOf { d -> d.toMinutes() } / 60.0 },
            )
        }
    }

    /**
     * Averages over [daily], excluding [today]: today is still in progress
     * for BPM (more readings may still arrive) and, per an explicit user
     * request, the "weekly" average must mean the days *before* today, not a
     * half-finished one silently pulling it down. A day with no reading is
     * excluded from the average entirely — never counted as a zero, which
     * would otherwise pull the average down every time coverage is sparse.
     */
    fun computeAverages(daily: List<DailyHealthReading>, today: LocalDate): WeeklyHealthAverages {
        val pastDays = daily.filter { it.date != today }
        val bpmValues = pastDays.mapNotNull { it.heartRateBpm }
        val sleepValues = pastDays.mapNotNull { it.sleepHours }
        return WeeklyHealthAverages(
            avgHeartRateBpm = if (bpmValues.isNotEmpty()) bpmValues.average().roundToLong() else null,
            avgSleepPerNight = if (sleepValues.isNotEmpty()) Duration.ofMinutes((sleepValues.average() * 60.0).roundToLong()) else null,
        )
    }
}
