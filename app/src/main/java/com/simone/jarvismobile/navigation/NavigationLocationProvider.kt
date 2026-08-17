package com.simone.jarvismobile.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.BuildConfig
import com.simone.jarvismobile.core.navigation.GpsFix
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.SimulatedGpsRoute
import com.simone.jarvismobile.navigation.debug.DebugGpsSimulator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline GNSS position, straight from Android's [LocationManager] — no Play
 * Services, no network. It emits [GpsFix]es while collected and stops the moment
 * collection ends, so high-accuracy GPS is only on during an active navigation
 * (spec §15). Sampling interval is chosen by the caller so a background/lower
 * power mode can slow it down.
 */
@Singleton
class NavigationLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun gpsEnabled(): Boolean =
        runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    /**
     * A cold flow of fixes at ~[intervalMs]. Requires the fine-location permission;
     * emits nothing (and closes) if it is missing so the UI can prompt. GPS is the
     * only provider used — it needs no data connection.
     *
     * In a debug build with [DebugGpsSimulator.enabled] on, this substitutes a
     * synthetic driving loop instead — completely separate from this real-GPS
     * path, and impossible to trigger in a release build regardless of that
     * flag's in-memory state ([DebugGpsSimulator.setEnabled] is a no-op there).
     */
    fun fixes(intervalMs: Long = 1_000L, minDistanceM: Float = 0f): Flow<GpsFix> =
        if (BuildConfig.DEBUG && DebugGpsSimulator.enabled.value) simulatedFixes(intervalMs) else realFixes(intervalMs, minDistanceM)

    private fun simulatedFixes(intervalMs: Long): Flow<GpsFix> = flow {
        val route = SimulatedGpsRoute(origin = DEBUG_SIMULATION_ORIGIN)
        val startedAtMs = System.currentTimeMillis()
        while (true) {
            emit(route.fixAt(System.currentTimeMillis() - startedAtMs))
            delay(intervalMs)
        }
    }

    private fun realFixes(intervalMs: Long, minDistanceM: Float): Flow<GpsFix> = callbackFlow {
        if (!hasPermission()) { close(); return@callbackFlow }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toFix())
            }
            // Deprecated on newer APIs but harmless to keep for older devices.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        // Seed with the last known fix so the map can centre immediately.
        runCatching {
            if (hasPermission()) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { trySend(it.toFix()) }
            }
        }

        val ok = runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                minDistanceM,
                listener,
                context.mainLooper,
            )
            true
        }.getOrElse {
            Log.w(TAG, "location_updates_failed ${it.javaClass.simpleName}")
            false
        }
        if (!ok) { close(); return@callbackFlow }

        awaitClose { runCatching { locationManager.removeUpdates(listener) } }
    }

    private fun Location.toFix(): GpsFix = GpsFix(
        location = LatLng(latitude, longitude),
        accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        speedMps = if (hasSpeed()) speed else null,
        bearingDegrees = if (hasBearing()) bearing else null,
        timestampMs = time,
    )

    private companion object {
        const val TAG = "JarvisNavLocation"

        /** Rome — an arbitrary, fixed debug-simulation starting point. */
        val DEBUG_SIMULATION_ORIGIN = LatLng(41.9028, 12.4964)
    }
}
