package com.simone.jarvismobile.tools

import com.simone.jarvismobile.core.health.HealthPeriod
import com.simone.jarvismobile.core.tools.SensitivityLevel
import com.simone.jarvismobile.core.tools.Tool
import com.simone.jarvismobile.core.tools.ToolPolicy
import com.simone.jarvismobile.core.tools.ToolResult
import com.simone.jarvismobile.core.weather.italianLabel
import com.simone.jarvismobile.health.HealthConnectManager
import com.simone.jarvismobile.weather.WeatherManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
        "Meteo reale di oggi o di uno dei prossimi 3 giorni (temperatura, condizione, vento) dalla fonte configurata."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = true
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? {
        arguments.int("days_ahead")?.let { if (it !in 0..3) return "days_ahead fuori intervallo (0-3)" }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val outlook = weather.fetchWeeklyOutlook() ?: return ToolResult.Failure("weather_unavailable")
        val daysAhead = (arguments.int("days_ahead") ?: 0).coerceIn(0, 3)
        val dayLabel = when (daysAhead) {
            0 -> "oggi"
            1 -> "domani"
            else -> "tra $daysAhead giorni"
        }

        val spoken = if (daysAhead == 0) {
            val category = outlook.currentCategory
            val temp = outlook.currentTempC
            val wind = outlook.currentWindKmh
            if (category == null && temp == null) return ToolResult.Failure("weather_unavailable")
            val parts = listOfNotNull(
                category?.italianLabel,
                temp?.let { "${it.roundToInt()}°C" },
                wind?.let { "vento ${it.roundToInt()} km/h" },
            )
            "Oggi: ${parts.joinToString(", ")}"
        } else {
            val day = outlook.upcoming.getOrNull(daysAhead - 1) ?: return ToolResult.Failure("weather_unavailable")
            if (day.category == null && day.tempMaxC == null && day.tempMinC == null) {
                return ToolResult.Failure("weather_unavailable")
            }
            val temps = listOfNotNull(
                day.tempMaxC?.let { "max ${it.roundToInt()}°C" },
                day.tempMinC?.let { "min ${it.roundToInt()}°C" },
            )
            val parts = listOfNotNull(
                day.category?.italianLabel,
                temps.takeIf { it.isNotEmpty() }?.joinToString(" "),
                day.windKmh?.let { "vento ${it.roundToInt()} km/h" },
            )
            "${dayLabel.replaceFirstChar { it.uppercase() }}: ${parts.joinToString(", ")}"
        }
        return ok("day" to dayLabel, "spoken" to spoken.trim())
    }
}

/**
 * Real weekly sleep/resting-heart-rate summary, or a single night's own
 * reading, from the same [HealthConnectManager] the Ares dashboard already
 * reads — never a duplicated database, never a per-model estimate. A day
 * with no record is counted in [daysMissing], never silently treated as zero
 * hours slept (§ explicit constraint, "NON interpretare un giorno senza
 * record come 0 ore dormite" — already [HealthConnectManager]'s own contract
 * via nullable `sleepHours`/`heartRateBpm` per day, reused verbatim here
 * rather than re-derived). Fails honestly (never invents a number) when
 * Health Connect itself is unavailable on this device, the permission was
 * never granted, or there is genuinely no data yet.
 *
 * § FASE 2A.7 RELEASE GATE 4 real bug fix: this tool used to take no
 * temporal argument at all, so "stanotte"/"questa settimana"/"ultimi 7
 * giorni" all produced the identical weekly aggregate — "stanotte" was
 * silently answered with the week's average instead of last night's own
 * reading. The optional `period` argument (`"last_night"` | `"week"`, built
 * from [HealthPeriodParser][com.simone.jarvismobile.core.health.HealthPeriodParser]
 * by the capability router — parameter extraction stays separate from this
 * tool's own execution) now distinguishes the two; an absent/unrecognized
 * value defaults to `"week"`, the prior (and still correct for any
 * week-shaped phrasing) behavior, so any other caller offering this tool to
 * the model with no `period` at all is unaffected.
 */
class GetHealthSummaryTool(private val health: HealthConnectManager) : Tool {
    override val name = "get_health_summary"
    override val description =
        "Riepilogo reale da Health Connect: ore di sonno e frequenza cardiaca a riposo. " +
            "Argomento opzionale \"period\": \"last_night\" per il dato di stanotte, \"week\" (default) per la media/settimana."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!health.isAvailable) return ToolResult.Failure("health_unavailable")
        if (!health.hasPermissions()) return ToolResult.Failure("health_permission_missing")

        val snapshot = health.refresh() ?: health.cachedSnapshot()
            ?: return ToolResult.Failure("health_unavailable")

        val period = if (arguments.str("period") == "last_night") HealthPeriod.LAST_NIGHT else HealthPeriod.WEEK
        return when (period) {
            HealthPeriod.LAST_NIGHT -> lastNightResult(snapshot)
            HealthPeriod.WEEK -> weeklyResult(snapshot)
        }
    }

    /**
     * The most recent day in the series is "stanotte" regardless of when
     * during the day it is asked — [HealthDailySeries][com.simone.jarvismobile.core.health.HealthDailySeries]
     * attributes a sleep session to the day of its wake-up, so last night's
     * sleep (if Health Connect has synced it yet) always lands on today's
     * entry. Never falls back to the weekly average — a missing single-night
     * reading is a genuinely different answer ("no data for last night"),
     * not "here is the week instead".
     */
    private fun lastNightResult(snapshot: HealthConnectManager.HealthSnapshot): ToolResult {
        val last = snapshot.daily.lastOrNull()
        val hours = last?.sleepHours ?: return ToolResult.Failure("health_no_data")
        val totalMinutes = (hours * 60).roundToInt()
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        val spoken = "Stanotte hai dormito ${h}h ${m}min."
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "period" to JsonPrimitive("last_night"),
                    "sleep_hours" to JsonPrimitive(hours.toString()),
                    "spoken" to JsonPrimitive(spoken),
                ),
            ),
        )
    }

    private fun weeklyResult(snapshot: HealthConnectManager.HealthSnapshot): ToolResult {
        val daysWithSleep = snapshot.daily.count { it.sleepHours != null }
        val daysMissing = snapshot.daily.size - daysWithSleep
        val totalSleepHours = snapshot.daily.mapNotNull { it.sleepHours }.sum()
        val avgSleep = snapshot.averages.avgSleepPerNight
        val avgBpm = snapshot.averages.avgHeartRateBpm

        if (avgSleep == null && avgBpm == null && daysWithSleep == 0) {
            return ToolResult.Failure("health_no_data")
        }

        val parts = mutableListOf<String>()
        avgSleep?.let {
            val hours = it.toMinutes() / 60
            val minutes = it.toMinutes() % 60
            parts += "in media hai dormito ${hours}h ${minutes}min a notte"
        }
        if (daysWithSleep > 0) {
            parts += "dati di sonno reali per $daysWithSleep notti su ${snapshot.daily.size}" +
                if (daysMissing > 0) " ($daysMissing senza dato, mai contati come zero)" else ""
        }
        avgBpm?.let { parts += "frequenza cardiaca a riposo media $it bpm" }

        val spoken = parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "period" to JsonPrimitive("week"),
                    "days_with_sleep_data" to JsonPrimitive(daysWithSleep.toString()),
                    "days_missing" to JsonPrimitive(daysMissing.toString()),
                    "total_sleep_hours" to JsonPrimitive(totalSleepHours.toString()),
                    "avg_sleep_minutes" to JsonPrimitive((avgSleep?.toMinutes() ?: 0L).toString()),
                    "avg_resting_bpm" to JsonPrimitive(avgBpm?.toString() ?: ""),
                    "spoken" to JsonPrimitive(spoken),
                ),
            ),
        )
    }
}
