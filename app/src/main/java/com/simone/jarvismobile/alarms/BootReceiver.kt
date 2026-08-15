package com.simone.jarvismobile.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.automation.AutomationRepository
import com.simone.jarvismobile.automation.PlaceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms every alarm after a reboot or an app update.
 *
 * Alarms do not survive either: the system drops them all on shutdown, and
 * replacing the package clears them too. Reloading the agenda and the
 * automations rebuilds the whole schedule from the files, which are the source
 * of truth — so there is no separate alarm database to fall out of step.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootEntryPoint::class.java,
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                deps.agenda().reload()
                // Places first, so their geofences are cached before the
                // automations that arm them re-sync.
                deps.places().reload()
                deps.automations().reload()
                Log.i(TAG, "alarms_rearmed after=$action")
            } catch (e: Throwable) {
                Log.w(TAG, "alarm_rearm_failed ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun agenda(): AgendaRepository
        fun automations(): AutomationRepository
        fun places(): PlaceRepository
    }

    private companion object {
        const val TAG = "JarvisAlarms"
    }
}
