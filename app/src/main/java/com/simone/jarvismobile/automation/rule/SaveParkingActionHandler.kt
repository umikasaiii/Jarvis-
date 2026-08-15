package com.simone.jarvismobile.automation.rule

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.core.automation.rule.ActionRegistry
import com.simone.jarvismobile.core.automation.rule.ActionSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves where the vehicle was left (§16, phase 8).
 *
 * Reads the **last known** GNSS fix rather than waiting for a fresh one: this
 * action fires from a background trigger (a future "driving mode ended"), where
 * spinning up GPS and blocking for a fix is neither cheap nor reliable. The last
 * fix is good enough to find a car, and it keeps the action fast and GMS-free
 * (`LocationManager`, no Play Services). If there is no fix and no permission it
 * says so — it never reports a parking spot it did not record.
 */
@Singleton
class SaveParkingActionHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parking: ParkingRepository,
) : ActionHandler {
    override val type = ActionRegistry.SAVE_PARKING_LOCATION

    @SuppressLint("MissingPermission")
    override suspend fun handle(spec: ActionSpec, dryRun: Boolean): ActionOutcome {
        if (dryRun) return ActionOutcome.Skipped("dry-run: salverei dove ho parcheggiato")
        if (!hasFineLocation()) return ActionOutcome.Failed("permesso posizione mancante")
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ActionOutcome.Failed("posizione non disponibile")
        val fix = lastKnown(manager) ?: return ActionOutcome.Failed("nessuna posizione recente")
        return try {
            parking.save(
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyMeters = if (fix.hasAccuracy()) fix.accuracy else null,
                label = spec.param("label"),
            )
            ActionOutcome.Done
        } catch (e: Exception) {
            Log.w(TAG, "parking_save_failed ${e.javaClass.simpleName}")
            ActionOutcome.Failed(e.javaClass.simpleName)
        }
    }

    /** The most recent of GPS / network last-known fixes, whichever exists. */
    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): android.location.Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "JarvisAutomation"
    }
}
