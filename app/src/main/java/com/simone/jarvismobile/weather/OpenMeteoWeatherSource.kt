package com.simone.jarvismobile.weather

import android.util.Log
import com.simone.jarvismobile.core.weather.WeatherCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
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
 * else in the app. It never throws: any failure (no network, bad response,
 * timeout) returns null, which the caller treats as "unknown", never as "no
 * rain" — the same three-valued discipline the rest of the engine follows.
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

    override suspend fun fetchRain(latitude: Double, longitude: Double): RainForecast? =
        withContext(Dispatchers.IO) {
            runCatching { fetchOrThrow(latitude, longitude) }
                .onFailure { Log.w(TAG, "weather_fetch_failed ${it.javaClass.simpleName}") }
                .getOrNull()
        }

    override suspend fun fetchWeeklyOutlook(latitude: Double, longitude: Double): WeeklyOutlook? =
        withContext(Dispatchers.IO) {
            runCatching { fetchOutlookOrThrow(latitude, longitude) }
                .onFailure { Log.w(TAG, "weather_outlook_fetch_failed ${it.javaClass.simpleName}") }
                .getOrNull()
        }

    override suspend fun fetchHourlyForecast(
        latitude: Double,
        longitude: Double,
        dayIndex: Int,
    ): HourlyForecast? = withContext(Dispatchers.IO) {
        runCatching { fetchHourlyOrThrow(latitude, longitude, dayIndex) }
            .onFailure { Log.w(TAG, "weather_hourly_fetch_failed ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /** Throws on anything wrong; the caller wraps this in [runCatching]. */
    private fun fetchOrThrow(latitude: Double, longitude: Double): RainForecast? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${round(latitude)}&longitude=${round(longitude)}" +
            "&daily=weathercode,precipitation_sum&timezone=auto&forecast_days=2"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
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
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
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
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
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
     * ~1.1 km precision (2 decimals): enough for a local forecast, never the
     * user's exact address.
     */
    private fun round(coordinate: Double): String =
        String.format(Locale.US, "%.2f", coordinate)

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

    private companion object {
        const val TAG = "JarvisWeather"
        const val TIMEOUT_SECONDS = 10L
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
}
