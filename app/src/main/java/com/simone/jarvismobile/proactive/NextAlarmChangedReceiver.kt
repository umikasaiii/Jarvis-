package com.simone.jarvismobile.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * § FASE 2A.8 RELEASE GATE F — reacts to the device's next-alarm changing
 * (a new alarm set, an existing one edited, or the current one dismissed) so
 * [MorningTriggerScheduler]'s NEXT_ALARM firing follows promptly instead of
 * only self-healing at the next app cold start or the next time it itself
 * fires (both of which still cover the case where this receiver never runs —
 * see [MorningTriggerScheduler]'s own doc comment on why CONFIGURED_TIME is
 * a MANDATORY fallback, not merely a backup for this).
 *
 * Onestà: `android.app.action.NEXT_ALARM_CLOCK_CHANGED` is documented as a
 * broadcast a manifest-registered receiver can still receive after Android
 * 8's implicit-broadcast restrictions (it is not one of the explicitly
 * exempted-from-registration actions like `CONNECTIVITY_ACTION`), but this
 * has not been verified against a real device/compiler in this environment
 * (no network access, same limit as Health Connect/TomTom elsewhere in this
 * project) — if it turns out not to fire reliably on a given OEM build, the
 * self-healing re-arm above still bounds the staleness to one day, never
 * silently forever.
 */
class NextAlarmChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, NextAlarmEntryPoint::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                deps.morningTriggerScheduler().scheduleNextAlarmTrigger()
            } catch (e: Throwable) {
                Log.w(TAG, "next_alarm_changed_reschedule_failed ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NextAlarmEntryPoint {
        fun morningTriggerScheduler(): MorningTriggerScheduler
    }

    private companion object {
        const val TAG = "JarvisNextAlarm"
    }
}
