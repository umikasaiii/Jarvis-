package com.simone.jarvismobile.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.weather.RainDecision
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android side of the weather opt-in: reads the last-known position (no
 * active GPS fix — the same "good enough to be useful, cheap on battery"
 * choice [com.simone.jarvismobile.automation.rule.SaveParkingActionHandler]
 * makes for parking), fetches a rain forecast, and tells the [ContextEngine]
 * what it found. Off by default; does nothing at all unless the user has
 * explicitly turned the setting on.
 */
@Singleton
class WeatherManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val source: WeatherSource,
    private val contextEngine: ContextEngine,
) {
    @SuppressLint("MissingPermission")
    suspend fun refresh() {
        if (!settings.weatherEnabled.first()) return
        if (!hasFineLocation()) return
        val fix = lastKnownLocation() ?: run {
            Log.i(TAG, "weather_skip_no_fix")
            return
        }
        val forecast = source.fetchRain(fix.latitude, fix.longitude)
        contextEngine.onWeather(
            rainToday = RainDecision.fromProbabilityPercent(forecast?.todayPercent),
            rainTomorrow = RainDecision.fromProbabilityPercent(forecast?.tomorrowPercent),
        )
        Log.i(TAG, "weather_refreshed ok=${forecast != null}")
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
