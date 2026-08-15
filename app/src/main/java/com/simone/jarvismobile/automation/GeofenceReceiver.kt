package com.simone.jarvismobile.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Where a geofence transition lands.
 *
 * A rule armed a geofence whose request id is the rule's own id, so an ENTER
 * event names exactly the automations to run. The rule is read back from the
 * file first, because it may have been disabled or edited in Obsidian since it
 * was armed, and it runs through the same [AutomationRunner] as every other
 * trigger — a location rule is not a special kind of action, only a special
 * kind of "when".
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence_event_error code=${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val ids = event.triggeringGeofences?.map { it.requestId }?.filter { it.isNotBlank() }.orEmpty()
        if (ids.isEmpty()) return

        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GeofenceEntryPoint::class.java,
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repository = deps.automations()
                repository.reload()
                ids.forEach { id ->
                    val rule = repository.find(id) ?: return@forEach
                    if (!rule.enabled) return@forEach
                    if (deps.runner().run(rule)) repository.markFired(id)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "geofence_run_failed ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GeofenceEntryPoint {
        fun automations(): AutomationRepository
        fun runner(): AutomationRunner
    }

    private companion object {
        const val TAG = "JarvisAutomation"
    }
}
