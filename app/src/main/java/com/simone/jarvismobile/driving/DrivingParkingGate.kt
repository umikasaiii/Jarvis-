package com.simone.jarvismobile.driving

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.automation.rule.ParkingRepository
import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.core.navigation.Geo
import com.simone.jarvismobile.core.navigation.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where to leave the parking note when Modalità Guida ends (spec §18.7): saved
 * only when the vehicle is NOT near a place the user already knows by name —
 * casa, lavoro, or a saved place naming Francy. Those already answer "where's
 * the car", so a parking note there would just be noise. Reads the last-known
 * fix (same "good enough, cheap" choice as [com.simone.jarvismobile.automation.rule.SaveParkingActionHandler]);
 * clearing it back out once the user is home again is
 * [com.simone.jarvismobile.automation.rule.PlaceProximityReceiver]'s job, since
 * that is the real "arrivato a casa" signal, not something this gate can see.
 */
@Singleton
class DrivingParkingGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val places: PlaceRepository,
    private val parking: ParkingRepository,
) {
    suspend fun saveIfAway() {
        val fix = lastKnown() ?: return
        val here = LatLng(fix.latitude, fix.longitude)
        val nearKnownPlace = runCatching { places.enabled() }.getOrDefault(emptyList()).any { place ->
            isHomeLikePlace(place.displayName) &&
                Geo.distanceMeters(here, LatLng(place.latitude, place.longitude)) <= NEAR_METERS
        }
        if (nearKnownPlace) return
        runCatching {
            parking.save(
                latitude = here.lat,
                longitude = here.lon,
                accuracyMeters = if (fix.hasAccuracy()) fix.accuracy else null,
                label = "Modalità Guida",
            )
        }
    }

    private fun isHomeLikePlace(displayName: String): Boolean {
        val n = displayName.trim().lowercase()
        return n == "casa" || n == "lavoro" || n.contains("francy")
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private companion object {
        const val NEAR_METERS = 150.0
    }
}
