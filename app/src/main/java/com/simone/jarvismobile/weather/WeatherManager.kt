package com.simone.jarvismobile.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.weather.RainDecision
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android side of the weather opt-in: resolves a coordinate, fetches a rain
 * forecast, and tells the [ContextEngine] what it found. Off by default; does
 * nothing at all unless the user has explicitly turned the setting on.
 *
 * The coordinate is the user's chosen [SettingsRepository.weatherPlaceId] — one
 * of their saved [PlaceRepository] places — when they picked one (§ Impostazioni
 * › Meteo): a fixed point the forecast is actually meant for, rather than
 * whatever GPS/network happened to have last recorded, which could be a coarse
 * network fix from wherever the phone last got a location update (a trip, a
 * different room's Wi-Fi AP) and not reflect where the user actually is this
 * evening. Falls back to the last-known position only when no place is chosen.
 */
@Singleton
class WeatherManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val source: WeatherSource,
    private val contextEngine: ContextEngine,
    private val places: PlaceRepository,
) {
    @SuppressLint("MissingPermission")
    suspend fun refresh() {
        if (!settings.weatherEnabled.first()) return
        val point = resolvePoint() ?: run {
            Log.i(TAG, "weather_skip_no_fix")
            return
        }
        val forecast = source.fetchRain(point.first, point.second)
        contextEngine.onWeather(
            rainToday = RainDecision.fromForecast(forecast?.todayMeanProbabilityPercent, forecast?.todayMillimeters),
            rainTomorrow = RainDecision.fromForecast(
                forecast?.tomorrowMeanProbabilityPercent,
                forecast?.tomorrowMillimeters,
            ),
        )
        Log.i(TAG, "weather_refreshed ok=${forecast != null}")
    }

    /** The chosen saved place's coordinate, or the last-known fix as a fallback. */
    @SuppressLint("MissingPermission")
    private suspend fun resolvePoint(): Pair<Double, Double>? {
        val placeId = settings.weatherPlaceId.first()
        if (placeId.isNotBlank()) {
            val place = places.byId(placeId)
            if (place != null) return place.latitude to place.longitude
            Log.w(TAG, "weather_place_missing")
        }
        if (!hasFineLocation()) return null
        val fix = lastKnownLocation() ?: return null
        return fix.latitude to fix.longitude
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): android.location.Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "JarvisWeather"
    }
}
