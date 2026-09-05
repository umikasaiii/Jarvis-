package com.simone.jarvismobile.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.automation.AutomationRepository
import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.automation.rule.RuleScheduler
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
                deps.automations().reload()
                // The generic engine's clock triggers re-arm from Room, the source
                // of truth for the new rules.
                deps.ruleScheduler().sync()
                // Place geofences (proximity alerts) also do not survive a reboot.
                deps.places().reload()
                // § FASE 2A.8 §F — Multi-Signal Morning Coordinator's own
                // exact alarms are just as reboot-fragile as the ones above.
                deps.morningTriggerScheduler().scheduleAll()
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
        fun ruleScheduler(): RuleScheduler
        fun places(): PlaceRepository
        fun morningTriggerScheduler(): com.simone.jarvismobile.proactive.MorningTriggerScheduler
    }

    private companion object {
        const val TAG = "JarvisAlarms"
    }
}
