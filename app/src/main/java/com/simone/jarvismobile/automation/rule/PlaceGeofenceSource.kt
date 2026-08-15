package com.simone.jarvismobile.automation.rule

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms an OS geofence around each saved place and keeps them in step (phase 6).
 *
 * Deliberately **not** Google Play Services: this project reads GNSS straight
 * from [LocationManager] (see NavigationLocationProvider), and Android's own
 * [LocationManager.addProximityAlert] gives geofencing with the same no-GMS,
 * works-offline guarantee. The system evaluates the fence and broadcasts a
 * PendingIntent on enter/exit even while the app is dead — which is the whole
 * point of "quando arrivo a casa".
 *
 * The location equivalent of [RuleScheduler]: the one place a saved place
 * becomes a platform registration. It is permission-gated and inert without the
 * background-location grant — the rules stay stored and shown, they just do not
 * arm, exactly as the CapabilityManager reports.
 */
@Singleton
class PlaceGeofenceSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasForegroundLocation(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackgroundLocation(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && hasForegroundLocation()
        } else {
            hasForegroundLocation()
        }

    /** Re-points the geofences at exactly the enabled places, nothing else. */
    @SuppressLint("MissingPermission")
    fun sync(places: List<AutomationPlaceEntity>) {
        // Clear the lot and re-add: registration is idempotent and rare, so the
        // simplest correct thing beats bookkeeping a diff.
        prefs.getStringSet(KEY_REGISTERED, emptySet()).orEmpty().forEach { id ->
            runCatching { locationManager.removeProximityAlert(pendingIntent(id)) }
        }

        val desired = if (hasBackgroundLocation()) places.filter { it.enabled } else emptyList()
        for (place in desired) {
            runCatching {
                locationManager.addProximityAlert(
                    place.latitude,
                    place.longitude,
                    place.radiusMeters,
                    NEVER_EXPIRE,
                    pendingIntent(place.id),
                )
            }.onFailure { Log.w(TAG, "geofence_add_failed ${it.javaClass.simpleName}") }
        }
        prefs.edit().putStringSet(KEY_REGISTERED, desired.map { it.id }.toSet()).apply()
        if (places.any { it.enabled } && !hasBackgroundLocation()) {
            Log.i(TAG, "geofences_pending_permission places=${places.count { it.enabled }}")
        } else {
            Log.i(TAG, "geofences_armed=${desired.size}")
        }
    }

    private fun pendingIntent(placeId: String): PendingIntent {
        // Identity ignores extras, so the place id lives in the data URI. Mutable
        // because the system writes KEY_PROXIMITY_ENTERING into it before delivery.
        val intent = Intent(context, PlaceProximityReceiver::class.java).apply {
            action = PlaceProximityReceiver.ACTION_PROXIMITY
            data = Uri.parse("jarvis://place/$placeId")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, placeId.hashCode(), intent, flags)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "JarvisAutomation"
        const val PREFS = "jarvis_geofence"
        const val KEY_REGISTERED = "registered_places"
        const val NEVER_EXPIRE = -1L
    }
}
