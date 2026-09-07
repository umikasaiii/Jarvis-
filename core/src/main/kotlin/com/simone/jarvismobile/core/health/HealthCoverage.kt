package com.simone.jarvismobile.core.health

import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 5 (Health Range + Coverage +
 * Freshness Foundation), JARVIS-09/C33. The missing distinction between "this
 * date/range was actually queried against Health Connect (even if it came
 * back with nothing)" and "this date/range was never queried at all" —
 * collapsing both into the same `health_no_data` failure was the exact
 * ambiguity this phase closes (see `nightResult()`/`weeklySleepResult()` in
 * `app/`'s `GetHealthSummaryTool`). A [DailyHealthReading] entry existing for
 * a date, EVEN WITH null metric fields, already proves that date was
 * queried — [HealthDailySeries.dailySeries] always emits one entry per day in
 * whatever window it fetched, never omits a day just because it found
 * nothing there. Absence of an entry proves nothing either way — it is
 * [HealthDayCoverage.NotCovered], never inferred as "confirmed empty".
 */
sealed interface HealthDayCoverage {
    /** [date] was actually queried — [reading] may still hold null metric fields, a genuinely EMPTY (not unknown) result. */
    data class Covered(val reading: DailyHealthReading) : HealthDayCoverage

    /** [date] was never part of any successful query/cache read — absence here proves nothing about whether real data exists. */
    data object NotCovered : HealthDayCoverage
}

/**
 * How much of a multi-day range (e.g. the standard week window, see
 * [HealthDailySeries.windowed]) was actually queried, kept distinct from how
 * much of what WAS queried holds real data for one metric — the two axes
 * §1/§6/§11-test-11 require kept separate so a coverage gap (some days never
 * queried, e.g. because the cache is older than the window now being asked
 * about) can never present itself as full-range certainty.
 */
data class HealthRangeCoverage(val requestedDays: Int, val coveredDays: Int, val daysWithData: Int) {
    init {
        require(coveredDays in 0..requestedDays) { "coveredDays ($coveredDays) must be within 0..requestedDays ($requestedDays)" }
        require(daysWithData in 0..coveredDays) { "daysWithData ($daysWithData) must be within 0..coveredDays ($coveredDays)" }
    }

    /** Every day of the requested range was actually queried — a "this week" claim can be made without qualification. */
    val isFullyCovered: Boolean get() = coveredDays >= requestedDays

    /** At least one day of the requested range was actually queried — false means the range is genuinely [ToolOutcomeStatus][com.simone.jarvismobile.core.tools.ToolOutcomeStatus.DATA_UNAVAILABLE], never [SUCCESS_EMPTY][com.simone.jarvismobile.core.tools.ToolOutcomeStatus.SUCCESS_EMPTY]. */
    val hasAnyCoverage: Boolean get() = coveredDays > 0

    /** At least one covered day actually holds a value for the metric in question. */
    val hasAnyData: Boolean get() = daysWithData > 0
}

object HealthCoverage {
    /** [date]'s coverage state within [daily] — the ONE place a single-day/night lookup decides covered vs. not, never duplicated inline. */
    fun resolveDay(daily: List<DailyHealthReading>, date: LocalDate): HealthDayCoverage {
        val reading = daily.firstOrNull { it.date == date }
        return if (reading != null) HealthDayCoverage.Covered(reading) else HealthDayCoverage.NotCovered
    }

    /**
     * Coverage summary for a windowed range. [windowed] must already be
     * scoped to the requested range (see [HealthDailySeries.windowed]) —
     * never the raw, potentially wider, merged cache: a historical-sync-
     * enlarged cache must never make a "this week" query silently claim more
     * days than it actually asked for.
     */
    fun resolveRange(windowed: List<DailyHealthReading>, requestedDays: Int, hasData: (DailyHealthReading) -> Boolean): HealthRangeCoverage {
        val coveredDays = windowed.size.coerceAtMost(requestedDays)
        return HealthRangeCoverage(
            requestedDays = requestedDays,
            coveredDays = coveredDays,
            daysWithData = windowed.count(hasData).coerceAtMost(coveredDays),
        )
    }
}
