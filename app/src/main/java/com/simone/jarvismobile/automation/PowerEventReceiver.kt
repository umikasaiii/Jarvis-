package com.simone.jarvismobile.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.simone.jarvismobile.core.automation.Trigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Evaluates condition automations when the system says the power state changed.
 *
 * Android has no "tell me when the battery reaches 23%" broadcast, and the one
 * that reports every percentage point cannot be declared in a manifest — it is
 * delivered only to a running app, by design, because waking every app on every
 * percent would be ruinous. So the thresholds are checked at the moments the
 * system does wake us: charger in, charger out, and the low-battery warning.
 *
 * The consequence is honest and worth stating: a rule at 23% fires when one of
 * those events happens below 23%, not the instant the meter ticks over. A rule
 * at 15% is close to exact, because that is when Android itself warns.
 */
class PowerEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PowerEntryPoint::class.java,
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repository = deps.automations()
                val rules = repository.reload().filter { it.enabled }
                if (rules.isEmpty()) return@launch

                val level = batteryPercent(context)
                val charging = action == Intent.ACTION_POWER_CONNECTED

                rules.forEach { rule ->
                    val due = when (val trigger = rule.trigger) {
                        is Trigger.ChargingStarted -> charging
                        is Trigger.BatteryBelow -> level in 0..trigger.percent && !charging
                        else -> false
                    }
                    if (!due) return@forEach
                    // A threshold rule would otherwise re-fire on every unplug
                    // while the battery stays low.
                    if (firedRecently(rule.lastFired)) return@forEach
                    if (deps.runner().run(rule)) repository.markFired(rule.id)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "power_event_failed ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun firedRecently(at: java.time.LocalDateTime?): Boolean =
        at != null && java.time.Duration.between(at, java.time.LocalDateTime.now())
            .toMinutes() < COOLDOWN_MINUTES

    private fun batteryPercent(context: Context): Int {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PowerEntryPoint {
        fun automations(): AutomationRepository
        fun runner(): AutomationRunner
    }

    private companion object {
        const val TAG = "JarvisAutomation"
        const val COOLDOWN_MINUTES = 60L
    }
}
