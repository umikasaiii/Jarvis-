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
 * § FASE 2A.8 §F / PROACTIVITY RELIABILITY CLOSURE WORK PACKAGE B §8 — a
 * manifest-registered SELF-HEALING FALLBACK for the device's next-alarm
 * changing. § WORK PACKAGE B §8 moved the PRIMARY observation of this signal
 * to a runtime-registered receiver inside
 * [com.simone.jarvismobile.automation.AutomationEventService] (which also
 * reconciles immediately on service start, closing the window before this
 * manifest receiver would ever get a chance to run) — this one remains
 * only as a bound on staleness for whoever hasn't enabled "Automazioni in
 * background": if it turns out this manifest broadcast does not fire
 * reliably on a given OEM build, [ProactiveScheduler.scheduleAll]'s own
 * boot/app-start re-arm still bounds the staleness to one day, never
 * silently forever. Both paths call the exact same
 * [ProactiveScheduler.reconcileNextAlarm] — the single canonical NEXT_ALARM
 * owner (§3) — never a second, competing scheduling decision.
 */
class NextAlarmChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, NextAlarmEntryPoint::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                deps.proactiveScheduler().reconcileNextAlarm()
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
        fun proactiveScheduler(): ProactiveScheduler
    }

    private companion object {
        const val TAG = "JarvisNextAlarm"
    }
}
