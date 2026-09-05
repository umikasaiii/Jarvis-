package com.simone.jarvismobile.tools

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.simone.jarvismobile.core.health.HealthAggregation
import com.simone.jarvismobile.core.health.HealthMetric
import com.simone.jarvismobile.core.health.HealthRange
import com.simone.jarvismobile.core.tools.SensitivityLevel
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
        if (!health.isAvailable) return ToolResult.Failure("health_unavailable")
        if (!health.hasPermissions()) return ToolResult.Failure("health_permission_missing")

        val snapshot = health.refresh() ?: health.cachedSnapshot()
            ?: return ToolResult.Failure("health_unavailable")

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
     */
    private fun nightResult(snapshot: HealthConnectManager.HealthSnapshot, date: LocalDate, metric: HealthMetric): ToolResult {
        val day = snapshot.daily.firstOrNull { it.date == date } ?: return ToolResult.Failure("health_no_data")
        val dateLabel = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.ITALIAN))
        return when (metric) {
            HealthMetric.SLEEP_DURATION -> {
                val hours = day.sleepHours ?: return ToolResult.Failure("health_no_data")
                val totalMinutes = (hours * 60).roundToInt()
                val spoken = "Il $dateLabel hai dormito ${totalMinutes / 60}h ${totalMinutes % 60}min."
                ok("range" to date.toString(), "sleep_hours" to hours.toString(), "spoken" to spoken)
            }
            HealthMetric.RESTING_HEART_RATE -> {
                val bpm = day.heartRateBpm ?: return ToolResult.Failure("health_no_data")
                val spoken = "Il $dateLabel la tua frequenza cardiaca a riposo era $bpm bpm."
                ok("range" to date.toString(), "resting_bpm" to bpm.toString(), "spoken" to spoken)
            }
        }
    }

    private fun weeklyResult(snapshot: HealthConnectManager.HealthSnapshot, metric: HealthMetric, aggregation: HealthAggregation): ToolResult {
        return when (metric) {
            HealthMetric.SLEEP_DURATION -> weeklySleepResult(snapshot, aggregation)
            // A "total" resting heart rate across a week has no meaningful
            // reading (summing BPM samples is not a real quantity) — the
            // average is the only sensible weekly view for this metric,
            // regardless of which aggregation was asked for.
            HealthMetric.RESTING_HEART_RATE -> weeklyBpmResult(snapshot)
        }
    }

    private fun weeklySleepResult(snapshot: HealthConnectManager.HealthSnapshot, aggregation: HealthAggregation): ToolResult {
        val daysWithSleep = snapshot.daily.count { it.sleepHours != null }
        val daysMissing = snapshot.daily.size - daysWithSleep
        if (daysWithSleep == 0) return ToolResult.Failure("health_no_data")

        val totalSleepHours = snapshot.daily.mapNotNull { it.sleepHours }.sum()
        val avgSleep = snapshot.averages.avgSleepPerNight

        val missingNote = if (daysMissing > 0) " ($daysMissing senza dato, mai contati come zero)" else ""
        val spoken = when (aggregation) {
            HealthAggregation.TOTAL -> {
                val totalMinutes = (totalSleepHours * 60).roundToInt()
                "Questa settimana hai dormito in totale ${totalMinutes / 60}h ${totalMinutes % 60}min, " +
                    "dati reali per $daysWithSleep notti su ${snapshot.daily.size}$missingNote."
            }
            HealthAggregation.AVERAGE -> {
                if (avgSleep == null) return ToolResult.Failure("health_no_data")
                "In media hai dormito ${avgSleep.toMinutes() / 60}h ${avgSleep.toMinutes() % 60}min a notte, " +
                    "dati reali per $daysWithSleep notti su ${snapshot.daily.size}$missingNote."
            }
        }
        return ok(
            "range" to "week",
            "aggregation" to aggregation.name.lowercase(),
            "days_with_sleep_data" to daysWithSleep.toString(),
            "days_missing" to daysMissing.toString(),
            "total_sleep_hours" to totalSleepHours.toString(),
            "avg_sleep_minutes" to (avgSleep?.toMinutes() ?: 0L).toString(),
            "spoken" to spoken,
        )
    }

    private fun weeklyBpmResult(snapshot: HealthConnectManager.HealthSnapshot): ToolResult {
        val avgBpm = snapshot.averages.avgHeartRateBpm ?: return ToolResult.Failure("health_no_data")
        val spoken = "In media la tua frequenza cardiaca a riposo questa settimana è stata $avgBpm bpm."
        return ok("range" to "week", "avg_resting_bpm" to avgBpm.toString(), "spoken" to spoken)
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

    private fun ramResult(): ToolResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return ToolResult.Failure("no_activity_service")
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = info.totalMem / BYTES_PER_GB
        val availGb = info.availMem / BYTES_PER_GB
        val spoken = "Il telefono ha ${gb(totalGb)} GB di RAM totale, di cui circa ${gb(availGb)} GB disponibili ora."
        return ok(
            "total_ram_gb" to "%.2f".format(Locale.ROOT, totalGb),
            "available_ram_gb" to "%.2f".format(Locale.ROOT, availGb),
            "spoken" to spoken,
        )
    }

    private fun storageResult(): ToolResult {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalGb = stat.totalBytes / BYTES_PER_GB
        val freeGb = stat.availableBytes / BYTES_PER_GB
        val spoken = "Il telefono ha ${gb(totalGb)} GB di spazio di archiviazione totale, di cui ${gb(freeGb)} GB liberi."
        return ok(
            "total_storage_gb" to "%.2f".format(Locale.ROOT, totalGb),
            "free_storage_gb" to "%.2f".format(Locale.ROOT, freeGb),
            "spoken" to spoken,
        )
    }

    private fun androidVersionResult(): ToolResult {
        val spoken = "Il telefono usa Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})."
        return ok(
            "android_release" to Build.VERSION.RELEASE,
            "android_sdk_int" to Build.VERSION.SDK_INT.toString(),
            "spoken" to spoken,
        )
    }

    private fun deviceModelResult(): ToolResult {
        val spoken = "Il telefono è un ${Build.MANUFACTURER} ${Build.MODEL}."
        return ok("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "spoken" to spoken)
    }

    private fun gb(value: Double): String = "%.1f".format(Locale.ITALIAN, value)

    private companion object {
        const val BYTES_PER_GB = 1_073_741_824.0
        val SUPPORTED_METRICS = setOf("ram", "storage", "android_version", "device_model")
    }
}
