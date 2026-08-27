package com.simone.jarvismobile.weather

import android.util.Log
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
 * Today's and tomorrow's rain signal: [todayMeanProbabilityPercent]/
 * [tomorrowMeanProbabilityPercent] is the day's *average* chance of rain, and
 * [todayMillimeters]/[tomorrowMillimeters] is the expected accumulation — the
 * pair [com.simone.jarvismobile.core.weather.RainDecision.fromForecast] needs
 * to tell a real rainy day from a brief-shower spike. Null per field when
 * unknown.
 */
data class RainForecast(
    val todayMeanProbabilityPercent: Int?,
    val tomorrowMeanProbabilityPercent: Int?,
    val todayMillimeters: Double?,
    val tomorrowMillimeters: Double?,
)

/**
 * Fetches a two-day rain forecast for a rounded coordinate.
 *
 * Deliberately the one place in JARVIS that talks to the network for its own
 * sake — weather forecasting is inherently an online fact, unlike everything
 * else in the app. It never throws: any failure (no network, bad response,
 * timeout) returns null, which the caller treats as "unknown", never as "no
 * rain" — the same three-valued discipline the rest of the engine follows.
 *
 * Open-Meteo is used because it needs no API key and no account (nothing to
 * leak, nothing to configure), and the request carries only latitude/longitude
 * — already rounded by the caller — and nothing else about the user.
 *
 * Requests the day's *mean* probability and its expected accumulation in
 * millimetres, not the `..._max` aggregate used before: a `max` is a single
 * hour's spike and made the evening "domani pioverà" warning fire on most
 * dry days (a brief, low-confidence shower window was enough to clear the
 * threshold). `RainDecision.fromForecast` requires both to agree.
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
            "&daily=precipitation_probability_mean,precipitation_sum&timezone=auto&forecast_days=2"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
        val parsed = json.decodeFromString(OpenMeteoResponse.serializer(), body)
        val probabilities = parsed.daily?.precipitationProbabilityMean
        val millimeters = parsed.daily?.precipitationSum
        return RainForecast(
            todayMeanProbabilityPercent = probabilities?.getOrNull(0),
            tomorrowMeanProbabilityPercent = probabilities?.getOrNull(1),
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
        @SerialName("precipitation_probability_mean")
        val precipitationProbabilityMean: List<Int>? = null,
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
