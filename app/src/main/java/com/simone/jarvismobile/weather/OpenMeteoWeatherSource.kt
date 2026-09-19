package com.simone.jarvismobile.weather

import android.util.Log
import com.simone.jarvismobile.core.weather.ForecastFacts
import com.simone.jarvismobile.core.weather.ForecastFactsBuilder
import com.simone.jarvismobile.core.weather.HourlyPrecipitationEvidence
import com.simone.jarvismobile.core.weather.WeatherCategory
import com.simone.jarvismobile.core.weather.WeatherFailureReason
import com.simone.jarvismobile.core.weather.WeatherRequestOutcome
import com.simone.jarvismobile.core.weather.roundWeatherCoordinate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Today's and tomorrow's weather signal: [todayCategory]/[tomorrowCategory] is
 * the day's dominant condition (Open-Meteo's own WMO weather code, classified
 * by [WeatherCategory.fromWmoCode]), and [todayMillimeters]/[tomorrowMillimeters]
 * is the expected accumulation — the pair
 * [com.simone.jarvismobile.core.weather.RainDecision.isRainDay] needs to tell a
 * real rain/storm day from a stray trace. Null per field when unknown.
 */
data class RainForecast(
    val todayCategory: WeatherCategory?,
    val tomorrowCategory: WeatherCategory?,
    val todayMillimeters: Double?,
    val tomorrowMillimeters: Double?,
)

/**
 * A single future day's outlook (§ tema Ares, blocco Sistema): the day's
 * category, high/low, and wind. Null per field when the API omits it — never
 * a guessed value.
 */
data class DayOutlook(
    val category: WeatherCategory?,
    val tempMaxC: Double?,
    val tempMinC: Double?,
    val windKmh: Double?,
    val windDirectionDeg: Double?,
)

/**
 * Right-now conditions plus the next three days (§ tema Ares, blocco Sistema
 * — "oggi... sotto il tempo dei prossimi 3 giorni"), a separate fetch from
 * [RainForecast]/[WeatherSource.fetchRain]: that path is the tested, already-
 * correct rain/no-rain signal the automation engine and morning briefing
 * depend on, and this extension deliberately does not touch it — a second,
 * additive method instead of widening the existing one.
 */
data class WeeklyOutlook(
    val currentTempC: Double?,
    val currentCategory: WeatherCategory?,
    val currentWindKmh: Double?,
    val currentWindDirectionDeg: Double?,
    /** From Open-Meteo's own `is_day` flag — real day/night, not a local sunrise/
     *  sunset guess (§ tema Atena, richiesta esplicita: icone meteo diverse di
     *  giorno/notte). Null only if the API omits the field. */
    val currentIsDay: Boolean? = null,
    /** Tomorrow, the day after, three days out — in that order. */
    val upcoming: List<DayOutlook>,
)

/** One hour's reading inside an [HourlyForecast] (§ tema Atena: "aprirmi previsione
 *  meteo per tutte le 24h di quel giorno"). [hour] is 0-23, local time. */
data class HourlyReading(
    val hour: Int,
    val category: WeatherCategory?,
    val tempC: Double?,
    val isDay: Boolean?,
)

/** 24 hours for one calendar day, resolved from the same 4-day request as the
 *  weekly outlook — a separate, on-demand fetch (only called when the user
 *  actually taps a day icon), not prefetched for all 4 days on every screen
 *  open. */
data class HourlyForecast(
    val date: java.time.LocalDate,
    val hours: List<HourlyReading>,
)

/**
 * Fetches a two-day weather forecast for a rounded coordinate.
 *
 * Deliberately the one place in JARVIS that talks to the network for its own
 * sake — weather forecasting is inherently an online fact, unlike everything
 * else in the app. Every PUBLIC method here never throws: any failure (no
 * network, bad response — § PASSAGGIO 7, now including a real non-2xx HTTP
 * status, previously silently indistinguishable from "decoded to nothing" —
 * timeout) returns null, which the caller treats as "unknown", never as "no
 * rain" — the same three-valued discipline the rest of the engine follows.
 * [lastFetchErrorType] exposes WHY the most recent `null` happened (§4), for
 * a caller that needs to distinguish a genuine SOURCE_FAILURE from "nothing
 * attempted yet" — never a value that changes what `null` itself means here.
 *
 * Open-Meteo is used because it needs no API key and no account (nothing to
 * leak, nothing to configure), and the request carries only latitude/longitude
 * — already rounded by the caller — and nothing else about the user. It is
 * also, in Europe, backed by DWD ICON — a model with particularly good
 * regional coverage for Italy — rather than a screen-scrape of a portal site
 * (ilmeteo.it has no public API for this kind of automated use, and scraping
 * its HTML would be far more fragile than a documented JSON endpoint).
 *
 * Requests the day's *weather code* — the model's own single summary
 * judgement of the day (clear/cloudy/rain/storm/…), not a raw probability
 * aggregate this app invented and had to keep re-tuning thresholds for. An
 * earlier version thresholded a daily *mean* rain probability against
 * expected millimetres, which still produced frequent false "domani
 * pioverà" warnings: a mean can clear 50% on an otherwise dry day when a
 * few hours of a plausible afternoon shower drag the 24h average up.
 */
@Singleton
class OpenMeteoWeatherSource @Inject constructor() : WeatherSource {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 7 §4 (JARVIS-06/-15) —
     * the real reason class of the most recent failed request across any of
     * this source's four fetch methods, or null if the last attempted fetch
     * succeeded (or none has been attempted yet). `@Volatile` because a
     * caller (WeatherManager) reads it from a different coroutine/dispatcher
     * than the one that wrote it inside `withContext(Dispatchers.IO)`.
     * Deliberately ONE shared field across all four methods, the same
     * accepted approximation [com.simone.jarvismobile.health.HealthConnectManager]'s
     * own single `_diagnostic` already uses for BPM/sleep — a concurrent call
     * to a different method could in principle overwrite this before a
     * caller reads it, a small, disclosed race rather than four separate
     * fields for a currently single-caller-at-a-time usage pattern.
     */
    @Volatile
    private var lastErrorType: String? = null

    override fun lastFetchErrorType(): String? = lastErrorType

    override suspend fun fetchRain(latitude: Double, longitude: Double): RainForecast? =
        withContext(Dispatchers.IO) {
            runCatching { fetchOrThrow(latitude, longitude) }
                .onSuccess { lastErrorType = null }
                .onFailure {
                    lastErrorType = errorTag(it)
                    Log.w(TAG, "weather_fetch_failed ${it.javaClass.simpleName}")
                }
                .getOrNull()
        }

    override suspend fun fetchWeeklyOutlook(latitude: Double, longitude: Double): WeeklyOutlook? =
        withContext(Dispatchers.IO) {
            runCatching { fetchOutlookOrThrow(latitude, longitude) }
                .onSuccess { lastErrorType = null }
                .onFailure {
                    lastErrorType = errorTag(it)
                    Log.w(TAG, "weather_outlook_fetch_failed ${it.javaClass.simpleName}")
                }
                .getOrNull()
        }

    override suspend fun fetchHourlyForecast(
        latitude: Double,
        longitude: Double,
        dayIndex: Int,
    ): HourlyForecast? = withContext(Dispatchers.IO) {
        runCatching { fetchHourlyOrThrow(latitude, longitude, dayIndex) }
            .onSuccess { lastErrorType = null }
            .onFailure {
                lastErrorType = errorTag(it)
                Log.w(TAG, "weather_hourly_fetch_failed ${it.javaClass.simpleName}")
            }
            .getOrNull()
    }

    override suspend fun fetchExtendedDay(
        latitude: Double,
        longitude: Double,
        daysAhead: Int,
    ): DayOutlook? = withContext(Dispatchers.IO) {
        runCatching { fetchExtendedDayOrThrow(latitude, longitude, daysAhead) }
            .onSuccess { lastErrorType = null }
            .onFailure {
                lastErrorType = errorTag(it)
                Log.w(TAG, "weather_extended_fetch_failed ${it.javaClass.simpleName}")
            }
            .getOrNull()
    }

    /**
     * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
     * WORK PACKAGE D §6/§7/§8/§10. The one new EXTENDED request this work
     * package adds (§5/§6: extends the EXISTING provider adapter, never a
     * second Open-Meteo client) — the same `daily=` endpoint shape as
     * [fetchOrThrow], plus `daily.time` (so [targetDate] is matched
     * explicitly via [com.simone.jarvismobile.core.weather.ForecastDateMatcher],
     * never assumed at a fixed array position — §7's exact defect) and the
     * richer precipitation fields §6 lists. Deliberately its own
     * request-scoped [WeatherRequestOutcome] (§10) — never the shared
     * `@Volatile lastErrorType` the four pre-existing methods above use —
     * so a concurrent evaluation can never see another request's error.
     */
    override suspend fun fetchDatedDailyForecast(
        latitude: Double,
        longitude: Double,
        targetDate: LocalDate,
        locationRevision: String,
    ): WeatherRequestOutcome<ForecastFacts> = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${round(latitude)}&longitude=${round(longitude)}" +
            "&daily=weathercode,precipitation_sum,rain_sum,showers_sum,snowfall_sum," +
            "precipitation_probability_max,precipitation_hours" +
            "&timezone=auto&forecast_days=3"
        val fetchedAt = Instant.now()
        val body = try {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            response.use { r -> if (r.isSuccessful) r.body?.string() else null }
                ?: return@withContext WeatherRequestOutcome.Failure(WeatherFailureReason.NETWORK, "http_error")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "weather_dated_fetch_failed ${e.javaClass.simpleName}")
            return@withContext WeatherRequestOutcome.Failure(WeatherFailureReason.NETWORK, e.javaClass.simpleName)
        }
        val parsed = try {
            json.decodeFromString(OpenMeteoDatedResponse.serializer(), body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext WeatherRequestOutcome.Failure(WeatherFailureReason.INVALID_RESPONSE, e.javaClass.simpleName)
        }
        val daily = parsed.daily ?: return@withContext WeatherRequestOutcome.Failure(WeatherFailureReason.INVALID_RESPONSE, "no_daily_block")
        val facts = ForecastFactsBuilder.fromDaily(
            targetDate = targetDate,
            providerTimezone = parsed.timezone,
            locationRevision = locationRevision,
            fetchedAt = fetchedAt,
            times = daily.time,
            weatherCodes = daily.weatherCode,
            precipitationSum = daily.precipitationSum,
            rainSum = daily.rainSum,
            showersSum = daily.showersSum,
            snowfallSum = daily.snowfallSum,
            precipitationProbabilityMax = daily.precipitationProbabilityMax,
            precipitationHours = daily.precipitationHours,
        ) ?: return@withContext WeatherRequestOutcome.Failure(WeatherFailureReason.DATE_MISMATCH, "target_date_not_in_response")
        WeatherRequestOutcome.Success(facts)
    }

    /**
     * § §6/§17 — date-aligned hourly precipitation evidence, requested ONLY
     * to confirm/deny a daily storm code (§17/§18) — [ProactiveManager]
     * (`app/proactive/`) only calls this when a daily code actually needs
     * confirming, never prefetched. Minimum required hourly fields only
     * (§6: "do not request unrelated fields").
     */
    override suspend fun fetchAlignedHourlyEvidence(
        latitude: Double,
        longitude: Double,
        targetDate: LocalDate,
    ): WeatherRequestOutcome<List<HourlyPrecipitationEvidence>> = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${round(latitude)}&longitude=${round(longitude)}" +
            "&hourly=weathercode,precipitation_probability,rain,showers,precipitation" +
            "&timezone=auto&forecast_days=3"
        val body = try {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            response.use { r -> if (r.isSuccessful) r.body?.string() else null }
                ?: return@withContext WeatherRequestOutcome.Failure(WeatherFailureReason.NETWORK, "http_error")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "weather_hourly_evidence_fetch_failed ${e.javaClass.simpleName}")
            return@withContext WeatherRequestOutcome.Failure(WeatherFailureReason.NETWORK, e.javaClass.simpleName)
        }
        val parsed = try {
            json.decodeFromString(OpenMeteoHourlyEvidenceResponse.serializer(), body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext WeatherRequestOutcome.Failure(WeatherFailureReason.INVALID_RESPONSE, e.javaClass.simpleName)
        }
        val hourly = parsed.hourly ?: return@withContext WeatherRequestOutcome.Failure(WeatherFailureReason.INVALID_RESPONSE, "no_hourly_block")
        val times = hourly.time.orEmpty()
        val targetStr = targetDate.toString()
        val evidence = times.indices.mapNotNull { i ->
            val t = times.getOrNull(i) ?: return@mapNotNull null
            if (t.take(10) != targetStr) return@mapNotNull null
            val hour = t.drop(11).take(2).toIntOrNull() ?: return@mapNotNull null
            HourlyPrecipitationEvidence(
                date = targetDate,
                hour = hour,
                rawWeatherCode = hourly.weatherCode?.getOrNull(i),
                precipitationProbabilityPercent = hourly.precipitationProbability?.getOrNull(i),
                rainMm = hourly.rain?.getOrNull(i),
                showersMm = hourly.showers?.getOrNull(i),
                precipitationMm = hourly.precipitation?.getOrNull(i),
            )
        }
        WeatherRequestOutcome.Success(evidence)
    }

    /** `"http_<code>"` for a real HTTP failure (see [WeatherHttpException]), else the plain exception class name — same style already used for Core diagnostics elsewhere in this project. */
    private fun errorTag(e: Throwable): String = if (e is WeatherHttpException) "http_${e.code}" else e.javaClass.simpleName

    /**
     * § PASSAGGIO 7 §4 — thrown (never silently swallowed as a plain `null`)
     * on a non-2xx response, so a real provider-side failure is captured by
     * the SAME `runCatching`/[errorTag] path as a network exception instead
     * of being indistinguishable from "the network worked but decoded to
     * nothing" — the exact SOURCE_FAILURE-vs-everything-else ambiguity this
     * phase exists to close.
     */
    private class WeatherHttpException(val code: Int) : Exception("http_$code")

    /** Throws on anything wrong; the caller wraps this in [runCatching]. */
    private fun fetchOrThrow(latitude: Double, longitude: Double): RainForecast? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${round(latitude)}&longitude=${round(longitude)}" +
            "&daily=weathercode,precipitation_sum&timezone=auto&forecast_days=2"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else throw WeatherHttpException(r.code) } ?: return null
        val parsed = json.decodeFromString(OpenMeteoResponse.serializer(), body)
        val codes = parsed.daily?.weatherCode
        val millimeters = parsed.daily?.precipitationSum
        return RainForecast(
            todayCategory = WeatherCategory.fromWmoCode(codes?.getOrNull(0)),
            tomorrowCategory = WeatherCategory.fromWmoCode(codes?.getOrNull(1)),
            todayMillimeters = millimeters?.getOrNull(0),
            tomorrowMillimeters = millimeters?.getOrNull(1),
        )
    }

    /**
     * Throws on anything wrong; the caller wraps this in [runCatching]. A
     * second, independent request from [fetchOrThrow] — different fields
     * (`current_weather` for right-now, plus temperature/wind in `daily`),
     * `forecast_days=4` so `daily[0]` is today and `daily[1..3]` are the
     * three days the Ares panel shows below it.
     */
    private fun fetchOutlookOrThrow(latitude: Double, longitude: Double): WeeklyOutlook? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${round(latitude)}&longitude=${round(longitude)}" +
            "&daily=weathercode,temperature_2m_max,temperature_2m_min,windspeed_10m_max,winddirection_10m_dominant" +
            "&current_weather=true&timezone=auto&forecast_days=4"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else throw WeatherHttpException(r.code) } ?: return null
        val parsed = json.decodeFromString(OpenMeteoOutlookResponse.serializer(), body)
        val daily = parsed.daily
        val current = parsed.currentWeather
        fun dayAt(index: Int): DayOutlook = DayOutlook(
            category = WeatherCategory.fromWmoCode(daily?.weatherCode?.getOrNull(index)),
            tempMaxC = daily?.tempMax?.getOrNull(index),
            tempMinC = daily?.tempMin?.getOrNull(index),
            windKmh = daily?.windSpeedMax?.getOrNull(index),
            windDirectionDeg = daily?.windDirectionDominant?.getOrNull(index),
        )
        return WeeklyOutlook(
            currentTempC = current?.temperature,
            currentCategory = WeatherCategory.fromWmoCode(current?.weatherCode),
            currentWindKmh = current?.windSpeed,
            currentWindDirectionDeg = current?.windDirection,
            currentIsDay = current?.isDay?.let { it == 1 },
            upcoming = listOf(dayAt(1), dayAt(2), dayAt(3)),
        )
    }

    /**
     * Throws on anything wrong; the caller wraps this in [runCatching]. A
     * third, independent request — `hourly=` instead of `daily=`/`current_weather=`
     * — fetched only when the user actually taps a day icon (§ tema Atena),
     * never prefetched. [dayIndex] matches [WeeklyOutlook]'s own convention
     * (0 = today, 1..3 = the three [DayOutlook.category] cards), so the same
     * index the UI already has for a tapped day slices straight into this
     * response's flat 96-hour (4×24) arrays — no separate date math needed.
     */
    private fun fetchHourlyOrThrow(latitude: Double, longitude: Double, dayIndex: Int): HourlyForecast? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${round(latitude)}&longitude=${round(longitude)}" +
            "&hourly=temperature_2m,weathercode,is_day&timezone=auto&forecast_days=4"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else throw WeatherHttpException(r.code) } ?: return null
        val parsed = json.decodeFromString(OpenMeteoHourlyResponse.serializer(), body)
        val hourly = parsed.hourly ?: return null
        val times = hourly.time.orEmpty()
        val start = (dayIndex * 24).coerceIn(0, times.size)
        val end = ((dayIndex + 1) * 24).coerceIn(0, times.size)
        if (start >= end) return null
        val date = times.getOrNull(start)?.take(10)?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        } ?: return null
        val hours = (start until end).map { i ->
            HourlyReading(
                hour = i - start,
                category = WeatherCategory.fromWmoCode(hourly.weatherCode?.getOrNull(i)),
                tempC = hourly.temperature?.getOrNull(i),
                isDay = hourly.isDay?.getOrNull(i)?.let { it == 1 },
            )
        }
        return HourlyForecast(date, hours)
    }

    /**
     * § FASE 2A.8 RELEASE GATE H — the same `daily=` fields as
     * [fetchOutlookOrThrow], but with a real, requested `forecast_days`
     * instead of the fixed 4 the home dashboard needs, and returning only the
     * one day actually asked for. [daysAhead]=0 is today, matching every
     * other day-offset convention in this file. Deliberately does not touch
     * [fetchOutlookOrThrow]/its cache — a separate, additive request.
     *
     * Onestà: Open-Meteo's public free-tier forecast horizon is documented as
     * up to 16 days ([MAX_FORECAST_DAYS]) — not verified against a live
     * payload in this environment (`api.open-meteo.com` is blocked by this
     * sandbox's network proxy, the same limit already documented elsewhere in
     * this project for TomTom/Health Connect); a request beyond what the API
     * actually supports would come back with a normal HTTP error, handled
     * like any other failure here (`null`, never a crash or a guess).
     */
    private fun fetchExtendedDayOrThrow(latitude: Double, longitude: Double, daysAhead: Int): DayOutlook? {
        if (daysAhead < 0) return null
        val forecastDays = (daysAhead + 1).coerceAtMost(MAX_FORECAST_DAYS)
        if (daysAhead >= forecastDays) return null
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${round(latitude)}&longitude=${round(longitude)}" +
            "&daily=weathercode,temperature_2m_max,temperature_2m_min,windspeed_10m_max,winddirection_10m_dominant" +
            "&timezone=auto&forecast_days=$forecastDays"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else throw WeatherHttpException(r.code) } ?: return null
        val parsed = json.decodeFromString(OpenMeteoOutlookResponse.serializer(), body)
        val daily = parsed.daily ?: return null
        return DayOutlook(
            category = WeatherCategory.fromWmoCode(daily.weatherCode?.getOrNull(daysAhead)),
            tempMaxC = daily.tempMax?.getOrNull(daysAhead),
            tempMinC = daily.tempMin?.getOrNull(daysAhead),
            windKmh = daily.windSpeedMax?.getOrNull(daysAhead),
            windDirectionDeg = daily.windDirectionDominant?.getOrNull(daysAhead),
        )
    }

    /**
     * ~1.1 km precision (2 decimals): enough for a local forecast, never the
     * user's exact address. Delegates to the shared `:core` formula
     * ([roundWeatherCoordinate]) also used to build [WeatherLocationKey] cache
     * tags — one rounding rule, never two that could silently drift apart.
     */
    private fun round(coordinate: Double): String = roundWeatherCoordinate(coordinate)

    @Serializable
    private data class OpenMeteoResponse(val daily: Daily? = null)

    @Serializable
    private data class Daily(
        @SerialName("weathercode")
        val weatherCode: List<Int>? = null,
        @SerialName("precipitation_sum")
        val precipitationSum: List<Double>? = null,
    )

    @Serializable
    private data class OpenMeteoOutlookResponse(
        val daily: OutlookDaily? = null,
        @SerialName("current_weather")
        val currentWeather: CurrentWeather? = null,
    )

    @Serializable
    private data class OutlookDaily(
        @SerialName("weathercode")
        val weatherCode: List<Int>? = null,
        @SerialName("temperature_2m_max")
        val tempMax: List<Double>? = null,
        @SerialName("temperature_2m_min")
        val tempMin: List<Double>? = null,
        @SerialName("windspeed_10m_max")
        val windSpeedMax: List<Double>? = null,
        @SerialName("winddirection_10m_dominant")
        val windDirectionDominant: List<Double>? = null,
    )

    @Serializable
    private data class CurrentWeather(
        val temperature: Double? = null,
        @SerialName("weathercode")
        val weatherCode: Int? = null,
        @SerialName("windspeed")
        val windSpeed: Double? = null,
        @SerialName("winddirection")
        val windDirection: Double? = null,
        @SerialName("is_day")
        val isDay: Int? = null,
    )

    @Serializable
    private data class OpenMeteoHourlyResponse(val hourly: Hourly? = null)

    @Serializable
    private data class Hourly(
        val time: List<String>? = null,
        @SerialName("temperature_2m")
        val temperature: List<Double>? = null,
        @SerialName("weathercode")
        val weatherCode: List<Int>? = null,
        @SerialName("is_day")
        val isDay: List<Int>? = null,
    )

    /**
     * § WORK PACKAGE D §6/§24 — the top-level `timezone` field Open-Meteo
     * always returns when `timezone=auto` is requested, captured for real
     * (never assumed) provenance in [ForecastFacts.providerTimezone].
     */
    @Serializable
    private data class OpenMeteoDatedResponse(
        val timezone: String? = null,
        val daily: DatedDaily? = null,
    )

    @Serializable
    private data class DatedDaily(
        val time: List<String?>? = null,
        @SerialName("weathercode")
        val weatherCode: List<Int?>? = null,
        @SerialName("precipitation_sum")
        val precipitationSum: List<Double?>? = null,
        @SerialName("rain_sum")
        val rainSum: List<Double?>? = null,
        @SerialName("showers_sum")
        val showersSum: List<Double?>? = null,
        @SerialName("snowfall_sum")
        val snowfallSum: List<Double?>? = null,
        @SerialName("precipitation_probability_max")
        val precipitationProbabilityMax: List<Double?>? = null,
        @SerialName("precipitation_hours")
        val precipitationHours: List<Double?>? = null,
    )

    @Serializable
    private data class OpenMeteoHourlyEvidenceResponse(val hourly: HourlyEvidence? = null)

    @Serializable
    private data class HourlyEvidence(
        val time: List<String>? = null,
        @SerialName("weathercode")
        val weatherCode: List<Int?>? = null,
        @SerialName("precipitation_probability")
        val precipitationProbability: List<Double?>? = null,
        val rain: List<Double?>? = null,
        val showers: List<Double?>? = null,
        val precipitation: List<Double?>? = null,
    )

    private companion object {
        const val TAG = "JarvisWeather"
        const val TIMEOUT_SECONDS = 10L
        /** § FASE 2A.8 RELEASE GATE H — see [fetchExtendedDayOrThrow]'s own honesty note. */
        const val MAX_FORECAST_DAYS = 16
    }
}

/** Swappable so the fetch is never hardwired into the callers that use it. */
interface WeatherSource {
    /** Null on any failure; per-day values are null when the API omits them. */
    suspend fun fetchRain(latitude: Double, longitude: Double): RainForecast?

    /** Null on any failure — never a guessed/last-known outlook. */
    suspend fun fetchWeeklyOutlook(latitude: Double, longitude: Double): WeeklyOutlook?

    /** Null on any failure. [dayIndex] follows [WeeklyOutlook]'s convention:
     *  0 = today, 1..3 = [WeeklyOutlook.upcoming]'s three days. */
    suspend fun fetchHourlyForecast(latitude: Double, longitude: Double, dayIndex: Int): HourlyForecast?

    /**
     * § FASE 2A.8 RELEASE GATE H — one day, any offset up to the source's
     * real supported horizon (never limited to the home dashboard's fixed
     * 0-3 days) — used only by the chat-facing `get_weather` capability for
     * a horizon beyond what [fetchWeeklyOutlook] covers. Null on any
     * failure or when [daysAhead] is beyond what the source genuinely
     * supports — never a guessed/clamped day.
     */
    suspend fun fetchExtendedDay(latitude: Double, longitude: Double, daysAhead: Int): DayOutlook?

    /**
     * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
     * WORK PACKAGE D §6/§10. Normalized, dated forecast facts for
     * [targetDate] — never assumed at a fixed array position (§7). Its own
     * request-scoped [WeatherRequestOutcome], deliberately NOT folded into
     * [lastFetchErrorType]'s shared field (§10).
     */
    suspend fun fetchDatedDailyForecast(
        latitude: Double,
        longitude: Double,
        targetDate: java.time.LocalDate,
        locationRevision: String,
    ): WeatherRequestOutcome<ForecastFacts>

    /**
     * § §6/§17 — date-aligned hourly precipitation evidence for [targetDate],
     * used only to confirm/deny a daily storm code. Never prefetched.
     */
    suspend fun fetchAlignedHourlyEvidence(
        latitude: Double,
        longitude: Double,
        targetDate: java.time.LocalDate,
    ): WeatherRequestOutcome<List<HourlyPrecipitationEvidence>>

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 7 §4 (JARVIS-06/-15).
     * The real reason class of the most recent failed fetch (any of the four
     * methods above), or null when the last attempted fetch succeeded or none
     * has been attempted yet — lets a caller (e.g. `GetWeatherTool`) tell a
     * genuine provider/network SOURCE_FAILURE apart from "the coordinate
     * couldn't be resolved at all" (DATA_UNAVAILABLE) or "the response
     * decoded fine but this specific field/day was genuinely absent"
     * (SUCCESS_EMPTY) — three outcomes a bare `null` return cannot distinguish
     * on its own. Never a URL, a message, or any personal detail — a plain
     * exception-class-shaped tag, the same discipline already used for Health
     * Connect's diagnostic elsewhere in this project.
     */
    fun lastFetchErrorType(): String?
}
