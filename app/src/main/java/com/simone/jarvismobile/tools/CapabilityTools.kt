package com.simone.jarvismobile.tools

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.simone.jarvismobile.core.health.HealthAggregation
import com.simone.jarvismobile.core.health.HealthCoverage
import com.simone.jarvismobile.core.health.HealthDailySeries
import com.simone.jarvismobile.core.health.HealthDayCoverage
import com.simone.jarvismobile.core.health.HealthMetric
import com.simone.jarvismobile.core.health.HealthRange
import com.simone.jarvismobile.core.tools.SensitivityLevel
import com.simone.jarvismobile.core.tools.StructuredToolResult
import com.simone.jarvismobile.core.tools.Tool
import com.simone.jarvismobile.core.tools.ToolPolicy
import com.simone.jarvismobile.core.tools.ToolResult
import com.simone.jarvismobile.core.presentation.WeatherPhrasing
import com.simone.jarvismobile.core.weather.WeatherDaysAhead
import com.simone.jarvismobile.core.weather.italianLabel
import com.simone.jarvismobile.health.HealthConnectManager
import com.simone.jarvismobile.weather.WeatherManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * § FASE 2A.5-bis root cause fix — "Che tempo fa domani?"/"Quante ore ho
 * dormito questa settimana?" used to reach the model with ZERO tools
 * selected (`toolDisponibili=0/53`, `famiglie=--`), because neither
 * capability was ever exposed as a [Tool] to the conversational engine even
 * though the underlying implementations ([WeatherManager],
 * [com.simone.jarvismobile.health.HealthConnectManager]) already existed and
 * are already used by the Ares theme's UI — the model had no way to ground
 * an answer and, per the FAST persona's "don't invent" rule with no tool to
 * obey it with, produced its own guess instead. These two tools reuse those
 * exact implementations (never a second weather/Health Connect client, never
 * a duplicated database) so the conversational engine sees the SAME real
 * data the dashboard already shows.
 */

private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? = str(key)?.trim()?.toIntOrNull()

private fun ok(vararg pairs: Pair<String, String>): ToolResult =
    ToolResult.Success(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 5 — [HealthCoverage] (`:core`)
 * operates on [com.simone.jarvismobile.core.health.DailyHealthReading], a
 * distinct (structurally identical, textually different) type from
 * [HealthConnectManager.DailyHealthReading] — the same map-to-core-then-back
 * pattern already established by [HealthConnectManager.mergeDaily]/
 * [HealthConnectManager.weeklyWindow], reused here instead of a second
 * conversion helper.
 */
private fun HealthConnectManager.DailyHealthReading.toCore() =
    com.simone.jarvismobile.core.health.DailyHealthReading(date, heartRateBpm, sleepHours)

/**
 * Real weather, today or up to 3 days ahead, from the same
 * [WeatherManager]/Open-Meteo pipeline the Ares dashboard already renders —
 * never a second weather source, never a guess. Returns [ToolResult.Failure]
 * (never a fabricated forecast) whenever the setting is off, no coordinate
 * could be resolved, or the fetch itself failed — [WeatherManager.fetchWeeklyOutlook]
 * already collapses all three into `null` by its own documented contract, so
 * this tool cannot and does not try to guess which one happened.
 */
class GetWeatherTool(private val weather: WeatherManager) : Tool {
    override val name = "get_weather"
    override val description =
        "Meteo reale di oggi o di uno dei prossimi ${WeatherDaysAhead.MAX_SUPPORTED_DAYS_AHEAD} giorni " +
            "(temperatura, condizione, vento) dalla fonte configurata."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = true
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? {
        arguments.int("days_ahead")?.let {
            if (it !in 0..WeatherDaysAhead.MAX_SUPPORTED_DAYS_AHEAD) {
                return "days_ahead fuori intervallo (0-${WeatherDaysAhead.MAX_SUPPORTED_DAYS_AHEAD})"
            }
        }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val daysAhead = (arguments.int("days_ahead") ?: 0).coerceIn(0, WeatherDaysAhead.MAX_SUPPORTED_DAYS_AHEAD)
        val dayLabel = when (daysAhead) {
            0 -> "oggi"
            1 -> "domani"
            else -> "tra $daysAhead giorni"
        }

        // § FASE 2A.8 RELEASE GATE H — home's own fixed 4-day window
        // (`fetchWeeklyOutlook`, untouched) still serves days 0-3, exactly as
        // before; only a horizon beyond it reaches the new, separate
        // `fetchExtendedDay` — never a second weather source, never a
        // silently-clamped nearer day.
        if (daysAhead <= 3) {
            val outlook = weather.fetchWeeklyOutlook() ?: return ToolResult.Failure("weather_unavailable")
            val spoken = if (daysAhead == 0) {
                if (outlook.currentCategory == null && outlook.currentTempC == null) {
                    return ToolResult.Failure("weather_unavailable")
                }
                spokenFor(
                    dayLabel = dayLabel,
                    category = outlook.currentCategory?.italianLabel,
                    currentTempC = outlook.currentTempC,
                    tempMaxC = null,
                    tempMinC = null,
                    windKmh = outlook.currentWindKmh,
                )
            } else {
                val day = outlook.upcoming.getOrNull(daysAhead - 1) ?: return ToolResult.Failure("weather_unavailable")
                spokenForDay(dayLabel, day) ?: return ToolResult.Failure("weather_unavailable")
            }
            return ok("day" to dayLabel, "spoken" to spoken.trim())
        }

        val day = weather.fetchExtendedDay(daysAhead) ?: return ToolResult.Failure("weather_unavailable")
        val spoken = spokenForDay(dayLabel, day) ?: return ToolResult.Failure("weather_unavailable")
        return ok("day" to dayLabel, "spoken" to spoken.trim())
    }

    /** Shared rendering for any future day (both the home-backed 1-3 range and the extended 4+ range) — same fields, one place. */
    private fun spokenForDay(dayLabel: String, day: com.simone.jarvismobile.weather.DayOutlook): String? {
        if (day.category == null && day.tempMaxC == null && day.tempMinC == null) return null
        return spokenFor(
            dayLabel = dayLabel,
            category = day.category?.italianLabel,
            currentTempC = null,
            tempMaxC = day.tempMaxC,
            tempMinC = day.tempMinC,
            windKmh = day.windKmh,
        )
    }

    /**
     * § FASE 2A.8 RELEASE GATE I — a pure presentation layer
     * ([com.simone.jarvismobile.core.presentation.WeatherPhrasing]) over
     * these SAME grounded fields, picking one of several natural Italian
     * phrasings instead of always the same fixed sentence — never a second
     * fabricated field, never an LLM round for a simple forecast.
     */
    private fun spokenFor(
        dayLabel: String,
        category: String?,
        currentTempC: Double?,
        tempMaxC: Double?,
        tempMinC: Double?,
        windKmh: Double?,
    ): String = WeatherPhrasing.render(
        templateIndex = kotlin.random.Random.nextInt(WeatherPhrasing.TEMPLATE_COUNT),
        dayLabel = dayLabel,
        category = category,
        currentTempC = currentTempC,
        tempMaxC = tempMaxC,
        tempMinC = tempMinC,
        windKmh = windKmh,
    )
}

/**
 * Real sleep/resting-heart-rate data from the same [HealthConnectManager] the
 * Ares dashboard already reads — never a duplicated database, never a
 * per-model estimate. A day with no record is counted as missing, never
 * silently treated as zero (§ explicit constraint, "NON interpretare un
 * giorno senza record come 0 ore dormite" — already [HealthConnectManager]'s
 * own contract via nullable `sleepHours`/`heartRateBpm` per day, reused
 * verbatim here rather than re-derived). Fails honestly (never invents a
 * number) when Health Connect itself is unavailable, the permission was
 * never granted, or there is genuinely no data for the requested range.
 *
 * § FASE 2A.8 RELEASE GATE D real bug fix: FASE 2A.7's `period` argument
 * (`last_night`/`week`) only distinguished two shapes — "Quante ore ho
 * dormito questa settimana?" (a TOTAL) and "Qual è la media del sonno questa
 * settimana?" (an AVERAGE) both landed on the same weekly-average answer,
 * and a specific past date ("il 2 settembre") had no representation at all.
 * Arguments now mirror [com.simone.jarvismobile.core.health.HealthQuerySpec]
 * directly — `metric` (`sleep_duration`/`resting_heart_rate`), `range`
 * (`week`, or an ISO `yyyy-MM-dd` for one specific night/day), `aggregation`
 * (`total`/`average`) — built by the capability router via
 * [com.simone.jarvismobile.core.health.HealthQueryParser] (parameter
 * extraction stays separate from this tool's own execution). Any argument
 * absent/unrecognized defaults to the previous safe behavior (`week`/`total`),
 * so another caller offering this tool with no arguments at all is unaffected.
 *
 * § JARVIS Implementation Master Plan — PASSAGGIO 5 (Health Range + Coverage +
 * Freshness Foundation, JARVIS-09/C33). This is the one migrated read path:
 * REQUESTED range vs. COVERED range (was this date/week ever actually
 * queried?) vs. real records vs. freshness are now kept explicit end to end,
 * closing the exact ambiguity `nightResult()` used to leave open — a date
 * never queried and a date queried-but-genuinely-empty both used to become
 * the identical `Failure("health_no_data")`. See
 * [com.simone.jarvismobile.core.health.HealthCoverage]/[com.simone.jarvismobile.core.health.HealthRangeCoverage]
 * (`:core`, pure, tested) for the coverage model itself.
 */
class GetHealthSummaryTool(private val health: HealthConnectManager) : Tool {
    override val name = "get_health_summary"
    override val description =
        "Dati reali da Health Connect: sonno o frequenza cardiaca a riposo. Argomenti opzionali: " +
            "\"metric\" (\"sleep_duration\" default, o \"resting_heart_rate\"), " +
            "\"range\" (\"week\" default, o una data \"yyyy-MM-dd\" per una notte specifica), " +
            "\"aggregation\" (\"total\" default, o \"average\", solo per range=week)."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? {
        val range = arguments.str("range")
        if (range != null && range != "week" && runCatching { LocalDate.parse(range) }.isFailure) {
            return "range non valido: usa \"week\" o una data yyyy-MM-dd"
        }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        // § PASSAGGIO 5 §3 — three distinct, never-collapsed reasons a
        // request cannot proceed at all: the Health Connect SDK itself isn't
        // present on this device (DATA_UNAVAILABLE — nothing wrong happened,
        // the capability simply doesn't exist here), the permission was
        // never granted/was revoked (PERMISSION_MISSING), or a genuine
        // read/sync exception occurred (SOURCE_FAILURE, checked below once we
        // know a refresh actually failed rather than just never having run).
        if (!health.isAvailable) {
            return ToolResult.Failure(
                "health_unavailable",
                evidence = StructuredToolResult.dataUnavailable(
                    sourceId = SOURCE_ID,
                    reasonCode = "health_connect_not_available",
                    retryable = false,
                ),
            )
        }
        if (!health.hasPermissions()) {
            return ToolResult.Failure(
                "health_permission_missing",
                evidence = StructuredToolResult.permissionMissing(sourceId = SOURCE_ID, reasonCode = "permission_not_granted"),
            )
        }

        val refreshed = health.refresh()
        val snapshot = refreshed ?: health.cachedSnapshot()
        if (snapshot == null) {
            // A real exception during the read/sync (recorded by
            // HealthConnectManager's own diagnostic, never inferred) is a
            // genuine SOURCE_FAILURE; no error recorded at all — this device
            // has simply never synced successfully yet — is a coverage gap,
            // DATA_UNAVAILABLE, never presented as a crash.
            val lastError = health.diagnostic.value?.lastErrorType
            return if (lastError != null) {
                ToolResult.Failure(
                    "health_source_failure",
                    evidence = StructuredToolResult.sourceFailure(sourceId = SOURCE_ID, reasonCode = lastError, retryable = true),
                )
            } else {
                ToolResult.Failure(
                    "health_unavailable",
                    evidence = StructuredToolResult.dataUnavailable(sourceId = SOURCE_ID, reasonCode = "not_synced_yet", retryable = true),
                )
            }
        }

        val metric = if (arguments.str("metric") == "resting_heart_rate") {
            HealthMetric.RESTING_HEART_RATE
        } else {
            HealthMetric.SLEEP_DURATION
        }
        val aggregation = if (arguments.str("aggregation") == "average") HealthAggregation.AVERAGE else HealthAggregation.TOTAL
        val rangeArg = arguments.str("range")
        val range = if (rangeArg != null && rangeArg != "week") {
            HealthRange.Night(LocalDate.parse(rangeArg))
        } else {
            HealthRange.Week
        }

        return when (range) {
            is HealthRange.Night -> nightResult(snapshot, range.date, metric)
            HealthRange.Week -> weeklyResult(snapshot, metric, aggregation)
        }
    }

    /**
     * One specific calendar day/night — [HealthDailySeries][com.simone.jarvismobile.core.health.HealthDailySeries]
     * attributes a sleep session to the day of its wake-up, so "stanotte" (=
     * today) and any past date both look up the SAME `daily` list by
     * [LocalDate], never a special-cased "most recent entry" path. Never
     * falls back to the weekly average — a missing single-night reading is a
     * genuinely different answer ("no data for that night"), not "here is
     * the week instead".
     *
     * § PASSAGGIO 5 §2/§3 — [com.simone.jarvismobile.core.health.HealthCoverage.resolveDay]
     * now distinguishes the two cases the old `firstOrNull { it.date == date }
     * ?: Failure("health_no_data")` collapsed into one: [date] genuinely never
     * queried ([HealthDayCoverage.NotCovered], a coverage gap — DATA_UNAVAILABLE)
     * vs. [date] queried and the metric field is simply `null`
     * ([HealthDayCoverage.Covered] with a null field — SUCCESS_EMPTY, a real
     * "no data that night" answer).
     */
    private fun nightResult(snapshot: HealthConnectManager.HealthSnapshot, date: LocalDate, metric: HealthMetric): ToolResult {
        val dateLabel = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.ITALIAN))
        val coverage = HealthCoverage.resolveDay(snapshot.daily.map { it.toCore() }, date)
        if (coverage !is HealthDayCoverage.Covered) {
            return ToolResult.Failure(
                "health_range_not_covered",
                evidence = StructuredToolResult.dataUnavailable(sourceId = SOURCE_ID, reasonCode = "range_not_covered", retryable = true),
            )
        }
        val day = coverage.reading
        return when (metric) {
            HealthMetric.SLEEP_DURATION -> {
                val hours = day.sleepHours
                if (hours == null) {
                    emptyResult(date.toString(), "Non risultano dati di sonno per il $dateLabel.", snapshot.updatedAtMs)
                } else {
                    val totalMinutes = (hours * 60).roundToInt()
                    val spoken = "Il $dateLabel hai dormito ${totalMinutes / 60}h ${totalMinutes % 60}min."
                    dataResult(date.toString(), spoken, snapshot.updatedAtMs, date.toString(), "sleep_hours" to hours.toString())
                }
            }
            HealthMetric.RESTING_HEART_RATE -> {
                val bpm = day.heartRateBpm
                if (bpm == null) {
                    emptyResult(date.toString(), "Non risulta la frequenza cardiaca a riposo per il $dateLabel.", snapshot.updatedAtMs)
                } else {
                    val spoken = "Il $dateLabel la tua frequenza cardiaca a riposo era $bpm bpm."
                    dataResult(date.toString(), spoken, snapshot.updatedAtMs, date.toString(), "resting_bpm" to bpm.toString())
                }
            }
        }
    }

    /** A genuinely empty (covered, zero records) single-day/night result — never used for an uncovered date. */
    private fun emptyResult(range: String, spoken: String, retrievedAt: Long?): ToolResult = ToolResult.Success(
        JsonObject(mapOf("range" to JsonPrimitive(range), "spoken" to JsonPrimitive(spoken))),
        evidence = StructuredToolResult.successEmpty(sourceId = SOURCE_ID, retrievedAt = retrievedAt, requestedRange = range),
    )

    /** A genuine single-day/night record. */
    private fun dataResult(range: String, spoken: String, retrievedAt: Long?, coverage: String?, field: Pair<String, String>): ToolResult = ToolResult.Success(
        JsonObject(mapOf("range" to JsonPrimitive(range), field.first to JsonPrimitive(field.second), "spoken" to JsonPrimitive(spoken))),
        evidence = StructuredToolResult.successData(
            payload = JsonObject(mapOf(field.first to JsonPrimitive(field.second))),
            sourceId = SOURCE_ID, retrievedAt = retrievedAt, coverage = coverage,
        ),
    )

    /**
     * § PASSAGGIO 5 §1/§6 — [windowed] is [HealthConnectManager.weeklyWindow]
     * (the exact same "this week" scope the Home tile's average already uses)
     * so a wider cache (after a historical sync) never lets a "this week"
     * claim silently count more days than were actually requested.
     */
    private fun weeklyResult(snapshot: HealthConnectManager.HealthSnapshot, metric: HealthMetric, aggregation: HealthAggregation): ToolResult {
        val windowed = health.weeklyWindow(snapshot.daily)
        val requestedDays = HealthDailySeries.DEFAULT_WINDOW_DAYS + 1
        return when (metric) {
            HealthMetric.SLEEP_DURATION -> weeklySleepResult(snapshot, windowed, requestedDays, aggregation)
            // A "total" resting heart rate across a week has no meaningful
            // reading (summing BPM samples is not a real quantity) — the
            // average is the only sensible weekly view for this metric,
            // regardless of which aggregation was asked for.
            HealthMetric.RESTING_HEART_RATE -> weeklyBpmResult(snapshot, windowed, requestedDays)
        }
    }

    private fun weeklySleepResult(
        snapshot: HealthConnectManager.HealthSnapshot,
        windowed: List<HealthConnectManager.DailyHealthReading>,
        requestedDays: Int,
        aggregation: HealthAggregation,
    ): ToolResult {
        val coverage = HealthCoverage.resolveRange(windowed.map { it.toCore() }, requestedDays) { it.sleepHours != null }
        val coverageLabel = "${coverage.coveredDays}/${coverage.requestedDays}"

        if (!coverage.hasAnyCoverage) {
            // § PASSAGGIO 5 §1/§3 — the whole requested week was never
            // queried at all (e.g. the cache is older than the window now
            // being asked about) — a real coverage gap, never presented as
            // "you slept zero hours".
            return ToolResult.Failure(
                "health_range_not_covered",
                evidence = StructuredToolResult.dataUnavailable(sourceId = SOURCE_ID, reasonCode = "range_not_covered", retryable = true),
            )
        }
        if (!coverage.hasAnyData) {
            // § JARVIS Implementation Master Plan PASSAGGIO 1 §7 — Health
            // Connect WAS reachable, the permission WAS granted, and the
            // query genuinely ran against real (if possibly partial)
            // coverage: zero real sleep records is a real, successful
            // answer ("you have no sleep data"), never a source/tool
            // failure. This used to be `ToolResult.Failure("health_no_data")`,
            // which made `GroundingGate` treat HEALTH as an unsatisfied
            // family and block an honestly answerable turn — the exact
            // "empty vs failure" bug this phase's outcome taxonomy exists to close.
            val spoken = "Non ho registrato dati di sonno per nessun giorno di questa settimana."
            return ToolResult.Success(
                JsonObject(
                    mapOf(
                        "range" to JsonPrimitive("week"),
                        "days_with_sleep_data" to JsonPrimitive(0),
                        "spoken" to JsonPrimitive(spoken),
                    ),
                ),
                evidence = StructuredToolResult.successEmpty(
                    sourceId = SOURCE_ID,
                    retrievedAt = snapshot.updatedAtMs,
                    requestedRange = "week",
                    coverage = coverageLabel,
                ),
            )
        }

        val totalSleepHours = windowed.mapNotNull { it.sleepHours }.sum()
        val avgSleep = snapshot.averages.avgSleepPerNight
        val daysWithSleep = coverage.daysWithData
        val daysMissing = coverage.coveredDays - daysWithSleep

        // § PASSAGGIO 5 §3/§7 — a coverage gap (some days of the week never
        // queried) is disclosed explicitly, distinct from `missingNote`
        // (days that WERE queried but simply hold no record).
        val partialNote = if (!coverage.isFullyCovered) {
            " Copertura parziale: dati sincronizzati solo per ${coverage.coveredDays} giorni su ${coverage.requestedDays}."
        } else {
            ""
        }
        val missingNote = if (daysMissing > 0) " ($daysMissing senza dato, mai contati come zero)" else ""
        val spoken = when (aggregation) {
            HealthAggregation.TOTAL -> {
                val totalMinutes = (totalSleepHours * 60).roundToInt()
                "Questa settimana hai dormito in totale ${totalMinutes / 60}h ${totalMinutes % 60}min, " +
                    "dati reali per $daysWithSleep notti su ${coverage.coveredDays}$missingNote.$partialNote"
            }
            HealthAggregation.AVERAGE -> {
                if (avgSleep == null) {
                    // Real data exists for the window (`hasAnyData` above),
                    // but none of it falls on a past day the average can use
                    // (§ `HealthDailySeries.computeAverages` excludes today) —
                    // a genuine empty answer for THIS aggregation, never a failure.
                    val spokenEmpty = "Non ho dati di sonno sufficienti per calcolare una media questa settimana."
                    return ToolResult.Success(
                        JsonObject(mapOf("range" to JsonPrimitive("week"), "spoken" to JsonPrimitive(spokenEmpty))),
                        evidence = StructuredToolResult.successEmpty(
                            sourceId = SOURCE_ID, retrievedAt = snapshot.updatedAtMs, requestedRange = "week", coverage = coverageLabel,
                        ),
                    )
                }
                "In media hai dormito ${avgSleep.toMinutes() / 60}h ${avgSleep.toMinutes() % 60}min a notte, " +
                    "dati reali per $daysWithSleep notti su ${coverage.coveredDays}$missingNote.$partialNote"
            }
        }
        val payload = JsonObject(
            mapOf(
                "days_with_sleep_data" to JsonPrimitive(daysWithSleep.toString()),
                "days_covered" to JsonPrimitive(coverage.coveredDays.toString()),
                "total_sleep_hours" to JsonPrimitive(totalSleepHours.toString()),
                "avg_sleep_minutes" to JsonPrimitive((avgSleep?.toMinutes() ?: 0L).toString()),
            ),
        )
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "range" to JsonPrimitive("week"),
                    "aggregation" to JsonPrimitive(aggregation.name.lowercase()),
                    "days_with_sleep_data" to JsonPrimitive(daysWithSleep.toString()),
                    "days_missing" to JsonPrimitive(daysMissing.toString()),
                    "total_sleep_hours" to JsonPrimitive(totalSleepHours.toString()),
                    "avg_sleep_minutes" to JsonPrimitive((avgSleep?.toMinutes() ?: 0L).toString()),
                    "spoken" to JsonPrimitive(spoken),
                ),
            ),
            evidence = if (coverage.isFullyCovered) {
                StructuredToolResult.successData(payload = payload, sourceId = SOURCE_ID, retrievedAt = snapshot.updatedAtMs, coverage = coverageLabel)
            } else {
                // § §1/§3/§11-test-11 — PARTIAL: the days that WERE covered
                // are real and returned above, but the range as a whole was
                // not fully queried — never presented with full-range
                // certainty.
                StructuredToolResult.partial(payload = payload, partialFailureReasons = listOf("range_partially_covered"), sourceId = SOURCE_ID)
            },
        )
    }

    private fun weeklyBpmResult(
        snapshot: HealthConnectManager.HealthSnapshot,
        windowed: List<HealthConnectManager.DailyHealthReading>,
        requestedDays: Int,
    ): ToolResult {
        val coverage = HealthCoverage.resolveRange(windowed.map { it.toCore() }, requestedDays) { it.heartRateBpm != null }
        val coverageLabel = "${coverage.coveredDays}/${coverage.requestedDays}"

        if (!coverage.hasAnyCoverage) {
            return ToolResult.Failure(
                "health_range_not_covered",
                evidence = StructuredToolResult.dataUnavailable(sourceId = SOURCE_ID, reasonCode = "range_not_covered", retryable = true),
            )
        }
        // § PASSAGGIO 1 §7 — same empty-vs-failure fix as `weeklySleepResult`:
        // no resting-heart-rate sample this (possibly partial) week, with
        // Health Connect reachable and permitted, is a genuine SUCCESS_EMPTY,
        // not a failure.
        val avgBpm = snapshot.averages.avgHeartRateBpm ?: run {
            val spoken = "Non ho registrato la frequenza cardiaca a riposo per nessun giorno di questa settimana."
            return ToolResult.Success(
                JsonObject(mapOf("range" to JsonPrimitive("week"), "spoken" to JsonPrimitive(spoken))),
                evidence = StructuredToolResult.successEmpty(
                    sourceId = SOURCE_ID,
                    retrievedAt = snapshot.updatedAtMs,
                    requestedRange = "week",
                    coverage = coverageLabel,
                ),
            )
        }
        val partialNote = if (!coverage.isFullyCovered) {
            " Copertura parziale: dati sincronizzati solo per ${coverage.coveredDays} giorni su ${coverage.requestedDays}."
        } else {
            ""
        }
        val spoken = "In media la tua frequenza cardiaca a riposo questa settimana è stata $avgBpm bpm.$partialNote"
        val payload = JsonObject(mapOf("avg_resting_bpm" to JsonPrimitive(avgBpm.toString())))
        return ToolResult.Success(
            JsonObject(mapOf("range" to JsonPrimitive("week"), "avg_resting_bpm" to JsonPrimitive(avgBpm.toString()), "spoken" to JsonPrimitive(spoken))),
            evidence = if (coverage.isFullyCovered) {
                StructuredToolResult.successData(payload = payload, sourceId = SOURCE_ID, retrievedAt = snapshot.updatedAtMs, coverage = coverageLabel)
            } else {
                StructuredToolResult.partial(payload = payload, partialFailureReasons = listOf("range_partially_covered"), sourceId = SOURCE_ID)
            },
        )
    }

    private companion object {
        const val SOURCE_ID = "health_connect"
    }
}

/**
 * § FASE 2A.8 RELEASE GATE C — real on-device metrics, never a phrase
 * hardcoded to answer "quanta RAM ho?": [android.app.ActivityManager.MemoryInfo]
 * for RAM, [android.os.StatFs] for storage, [android.os.Build] for Android
 * version/model — the same real Android APIs any system-info app reads, no
 * second source. Deliberately does NOT cover battery (already
 * [BatteryTool][com.simone.jarvismobile.tools.BatteryTool]'s job — not
 * duplicated here) and deliberately has NO "vram" metric: mobile Android
 * exposes no dedicated VRAM value distinct from unified RAM, so a request
 * for it must fail honestly (`invalid_metric`) rather than silently
 * answering with the RAM figure instead.
 */
class GetDeviceInfoTool(private val context: Context) : Tool {
    override val name = "get_device_info"
    override val description =
        "Informazioni reali sul telefono. Richiede l'argomento \"metric\": " +
            "\"ram\" (memoria RAM totale/disponibile), \"storage\" (spazio di archiviazione totale/libero), " +
            "\"android_version\" o \"device_model\"."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 1_000L

    override fun validate(arguments: JsonObject): String? {
        val metric = arguments.str("metric")
        if (metric !in SUPPORTED_METRICS) {
            return "metric mancante o non valido: usa ram, storage, android_version o device_model"
        }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult = when (arguments.str("metric")) {
        "ram" -> ramResult()
        "storage" -> storageResult()
        "android_version" -> androidVersionResult()
        "device_model" -> deviceModelResult()
        else -> ToolResult.Failure("invalid_metric")
    }

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 2 §5, the one
     * additional simple read-only vertical slice (alongside Health's already-
     * migrated weekly result): a synchronous local Android API read has no
     * concept of a remote source/staleness/coverage gap, so every field
     * beyond [ToolOutcomeStatus.SUCCESS_DATA] itself stays null (§ never
     * inventing metadata a source doesn't actually provide) — this proves
     * [GroundingGate] consuming a real SUCCESS_DATA status end-to-end,
     * complementing PASSAGGIO 1's SUCCESS_EMPTY slice.
     */
    private fun successData(spoken: String, vararg pairs: Pair<String, String>): ToolResult = ToolResult.Success(
        JsonObject((pairs.toList() + ("spoken" to spoken)).associate { it.first to JsonPrimitive(it.second) }),
        evidence = StructuredToolResult.successData(
            payload = JsonObject(pairs.toMap().mapValues { JsonPrimitive(it.value) }),
            sourceId = "android_os",
            retrievedAt = System.currentTimeMillis(),
        ),
    )

    private fun ramResult(): ToolResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return ToolResult.Failure("no_activity_service")
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = info.totalMem / BYTES_PER_GB
        val availGb = info.availMem / BYTES_PER_GB
        val spoken = "Il telefono ha ${gb(totalGb)} GB di RAM totale, di cui circa ${gb(availGb)} GB disponibili ora."
        return successData(
            spoken,
            "total_ram_gb" to "%.2f".format(Locale.ROOT, totalGb),
            "available_ram_gb" to "%.2f".format(Locale.ROOT, availGb),
        )
    }

    private fun storageResult(): ToolResult {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalGb = stat.totalBytes / BYTES_PER_GB
        val freeGb = stat.availableBytes / BYTES_PER_GB
        val spoken = "Il telefono ha ${gb(totalGb)} GB di spazio di archiviazione totale, di cui ${gb(freeGb)} GB liberi."
        return successData(
            spoken,
            "total_storage_gb" to "%.2f".format(Locale.ROOT, totalGb),
            "free_storage_gb" to "%.2f".format(Locale.ROOT, freeGb),
        )
    }

    private fun androidVersionResult(): ToolResult {
        val spoken = "Il telefono usa Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})."
        return successData(
            spoken,
            "android_release" to Build.VERSION.RELEASE,
            "android_sdk_int" to Build.VERSION.SDK_INT.toString(),
        )
    }

    private fun deviceModelResult(): ToolResult {
        val spoken = "Il telefono è un ${Build.MANUFACTURER} ${Build.MODEL}."
        return successData(spoken, "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL)
    }

    private fun gb(value: Double): String = "%.1f".format(Locale.ITALIAN, value)

    private companion object {
        const val BYTES_PER_GB = 1_073_741_824.0
        val SUPPORTED_METRICS = setOf("ram", "storage", "android_version", "device_model")
    }
}
