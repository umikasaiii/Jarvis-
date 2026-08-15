package com.simone.jarvismobile.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.core.automation.Trigger
import com.simone.jarvismobile.core.places.Place
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "arrivo a <luogo>" rules into OS geofences and keeps them in step.
 *
 * This is the location equivalent of [com.simone.jarvismobile.alarms.ExactAlarms]:
 * one place where a rule becomes a platform registration, so the callers stay
 * ignorant of Play Services. The geofence is evaluated by the system on-device;
 * nothing here polls location or sends it anywhere.
 *
 * Two repositories feed it — the automations and the places — and either can
 * change independently, so both are cached and [rebuild] recomputes the whole
 * geofence set from the pair. Keeping no repository reference in this class is
 * deliberate: it is what stops a cycle (a place change re-syncs geofences, and a
 * geofence needs the places).
 *
 * Everything is permission-gated. With no background-location grant the class is
 * inert: the rules are still stored and shown, they simply do not arm until the
 * user allows it and saves the place they mention.
 */
@Singleton
class LocationTriggers @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client: GeofencingClient by lazy { LocationServices.getGeofencingClient(context) }

    @Volatile private var cachedAutomations: List<Automation> = emptyList()
    @Volatile private var cachedPlaces: List<Place> = emptyList()

    fun syncAutomations(automations: List<Automation>) {
        cachedAutomations = automations
        rebuild()
    }

    fun syncPlaces(places: List<Place>) {
        cachedPlaces = places
        rebuild()
    }

    /** True when a geofence can actually be armed right now. */
    fun canArm(): Boolean = hasForegroundLocation() && hasBackgroundLocation()

    fun hasForegroundLocation(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackgroundLocation(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            // Before Android 10 there is no separate background grant.
            hasForegroundLocation()
        }

    /** Enabled arrival rules whose named place has coordinates. */
    fun armableCount(): Int = desiredGeofences().size

    /** Arrival rules that cannot arm because their place is not defined yet. */
    fun rulesMissingPlace(): List<Automation> {
        val known = cachedPlaces.map { it.key }.toSet()
        return cachedAutomations.filter { it.enabled }
            .filter { val t = it.trigger; t is Trigger.ArrivedAt && Place.normalize(t.place) !in known }
    }

    // canArm() gates every registration; the SecurityException path stays too,
    // for a revoke that races the check.
    @SuppressLint("MissingPermission")
    private fun rebuild() {
        val desired = desiredGeofences()
        // Clear the lot and re-add: registration is idempotent and rare, so the
        // simplest correct thing beats bookkeeping a diff.
        runCatching { client.removeGeofences(pendingIntent()) }
        if (desired.isEmpty() || !canArm()) {
            if (desired.isNotEmpty()) Log.i(TAG, "geofences_pending permission=${canArm()} count=${desired.size}")
            return
        }
        val request = GeofencingRequest.Builder()
            // No INITIAL_TRIGGER_ENTER: arriving means crossing in, not being told
            // you are already home the moment the rule is created.
            .setInitialTrigger(0)
            .addGeofences(desired)
            .build()
        try {
            client.addGeofences(request, pendingIntent())
                .addOnSuccessListener { Log.i(TAG, "geofences_armed count=${desired.size}") }
                .addOnFailureListener { Log.w(TAG, "geofences_arm_failed ${it.javaClass.simpleName}") }
        } catch (e: SecurityException) {
            // The permission can be revoked between the check and the call.
            Log.w(TAG, "geofences_arm_denied ${e.javaClass.simpleName}")
        }
    }

    private fun desiredGeofences(): List<Geofence> {
        val placeByKey = cachedPlaces.associateBy { it.key }
        return cachedAutomations
            .filter { it.enabled }
            .mapNotNull { rule ->
                val trigger = rule.trigger as? Trigger.ArrivedAt ?: return@mapNotNull null
                val place = placeByKey[Place.normalize(trigger.place)] ?: return@mapNotNull null
                Geofence.Builder()
                    // The rule id is the request id, so a transition maps straight
                    // back to the automation that owns it.
                    .setRequestId(rule.id)
                    .setCircularRegion(place.latitude, place.longitude, place.radiusMeters.toFloat())
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .build()
            }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java).setAction(ACTION_GEOFENCE)
        // Geofencing requires a mutable PendingIntent: the system writes the
        // transition into it before delivery.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_GEOFENCE = "com.simone.jarvismobile.GEOFENCE_FIRE"
        private const val TAG = "JarvisAutomation"
    }
}
