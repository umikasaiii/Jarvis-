package com.simone.jarvismobile.automation.rule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.automation.rule.TriggerEvent
import com.simone.jarvismobile.core.automation.rule.TriggerRegistry
import com.simone.jarvismobile.core.context.PlaceSignal
import com.simone.jarvismobile.core.context.SignalSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Where an OS proximity alert lands for a saved place (phase 6).
 *
 * The place id travels in the data URI (the PendingIntent's identity ignores
 * extras); whether it is an arrival or a departure is the system's
 * [LocationManager.KEY_PROXIMITY_ENTERING] boolean. The observation is first fed
 * to the [ContextEngine] so "sono a casa" conditions can see it, then delivered
 * as a [TriggerEvent] to the same [AutomationExecutor] every other trigger uses —
 * a place event is a new *when*, not a new *what*.
 */
class PlaceProximityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROXIMITY) return
        val placeId = intent.data?.lastPathSegment?.takeIf { it.isNotBlank() } ?: return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)

        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProximityEntryPoint::class.java,
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val now = LocalDateTime.now()
                // On exit the phone is no longer *at* the place, so the fused
                // place becomes unknown rather than some other place.
                deps.contextEngine().onPlaceSignal(
                    PlaceSignal(
                        placeId = if (entering) placeId else null,
                        source = SignalSource.GEOFENCE,
                        at = now,
                    ),
                )
                val type = if (entering) TriggerRegistry.PLACE_ENTER else TriggerRegistry.PLACE_EXIT
                // The place is the dedup key, so a bouncing fix does not double-fire.
                val event = TriggerEvent(type = type, at = now, dedupKey = placeId)
                val evalContext = deps.contextEngine().evaluationContext(now = now)
                deps.ruleExecutor().onTrigger(event, evalContext)

                // Arriving home is the real "found the car" signal for a parking
                // note left by Modalità Guida (spec §18.7) — clear it here rather
                // than on a timer, so it never outlives its usefulness.
                if (entering && placeId == "casa") {
                    runCatching { deps.parkingRepository().clear() }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "proximity_failed ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProximityEntryPoint {
        fun contextEngine(): ContextEngine
        fun ruleExecutor(): AutomationExecutor
        fun parkingRepository(): ParkingRepository
    }

    companion object {
        const val ACTION_PROXIMITY = "com.simone.jarvismobile.PROXIMITY"
        private const val TAG = "JarvisAutomation"
    }
}
