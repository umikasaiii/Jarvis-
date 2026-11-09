package com.simone.jarvismobile.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.simone.jarvismobile.core.reliability.RearmTrigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-arms every alarm after a reboot or an app update (the system drops them all on shutdown and when the package is
 * replaced). The agenda, automations, rules, places and briefing plans are rebuilt from their own sources of truth;
 * there is no separate alarm database to fall out of step.
 *
 * BETA 7: the old version ran all five reloads in a single `try` inside an unmanaged coroutine. One failing step
 * skipped every later one (including the briefing alarms) and nothing recorded it. Now:
 *  1. a persistent [RearmWorker] is enqueued FIRST, so if the process is killed mid-way the pass still completes;
 *  2. the pass itself ([AlarmRearmer]) isolates and bounds every step and persists its report;
 *  3. `goAsync()` is finished in all cases, within a bounded wait.
 * The receiver work is only valid for the `goAsync()` window — that is exactly why step 1 exists.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> RearmTrigger.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> RearmTrigger.PACKAGE_REPLACED
            else -> return
        }
        rearmFrom(context, trigger)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun rearmer(): AlarmRearmer
    }

    companion object {
        private const val TAG = "JarvisAlarms"
        private const val RECEIVER_BUDGET_MS = 9_000L

        /** Shared by [BootReceiver] and [TimeChangeReceiver]; see the class doc for the ordering. */
        internal fun BroadcastReceiver.rearmFrom(context: Context, trigger: RearmTrigger) {
            runCatching { RearmWorker.enqueue(context.applicationContext, trigger) }
                .onFailure { Log.w(TAG, "rearm_worker_enqueue_failed ${it.javaClass.simpleName}") }
            val deps = EntryPointAccessors.fromApplication(context.applicationContext, BootEntryPoint::class.java)
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val report = withTimeoutOrNull(RECEIVER_BUDGET_MS) { deps.rearmer().rearm(trigger) }
                    if (report == null) Log.w(TAG, "rearm_over_budget trigger=${trigger.name} — worker will finish it")
                } catch (e: Throwable) {
                    Log.w(TAG, "rearm_failed ${e.javaClass.simpleName}")
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
