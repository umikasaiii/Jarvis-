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

/** Today's and tomorrow's rain-chance percentage, or null per day when unknown. */
data class RainForecast(val todayPercent: Int?, val tomorrowPercent: Int?)

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
            "&daily=precipitation_probability_max&timezone=auto&forecast_days=2"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
        val parsed = json.decodeFromString(OpenMeteoResponse.serializer(), body)
        val values = parsed.daily?.precipitationProbabilityMax
        return RainForecast(todayPercent = values?.getOrNull(0), tomorrowPercent = values?.getOrNull(1))
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
        @SerialName("precipitation_probability_max")
        val precipitationProbabilityMax: List<Int>? = null,
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
