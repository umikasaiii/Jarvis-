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

    private companion object {
        const val TAG = "JarvisWeather"
        const val TIMEOUT_SECONDS = 10L
    }
}

/** Swappable so the fetch is never hardwired into the callers that use it. */
interface WeatherSource {
    /** Null on any failure; per-day values are null when the API omits them. */
    suspend fun fetchRain(latitude: Double, longitude: Double): RainForecast?
}
