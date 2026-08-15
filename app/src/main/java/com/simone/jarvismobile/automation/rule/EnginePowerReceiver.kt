package com.simone.jarvismobile.automation.rule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.automation.rule.TriggerEvent
import com.simone.jarvismobile.core.automation.rule.TriggerRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Delivers charger connect/disconnect to the generic engine (a GMS-free,
 * manifest-declared event that Android keeps deliverable even to a stopped app).
 *
 * This is the new-engine counterpart of the old [com.simone.jarvismobile.automation.PowerEventReceiver]:
 * both receive the same system broadcast, each fires its own engine's rules, and
 * they never touch each other's state. The context snapshot is refreshed first so
 * a rule's "se in carica" condition sees the change it was woken by.
 */
class EnginePowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> TriggerRegistry.DEVICE_CHARGING
            Intent.ACTION_POWER_DISCONNECTED -> TriggerRegistry.DEVICE_UNPLUGGED
            else -> return
        }
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PowerEntryPoint::class.java,
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val now = LocalDateTime.now()
                deps.contextEngine().refreshDeviceState(now)
                deps.ruleExecutor().onTrigger(TriggerEvent(type = type, at = now), deps.contextEngine().evaluationContext(now = now))
            } catch (e: Throwable) {
                Log.w(TAG, "engine_power_failed ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PowerEntryPoint {
        fun contextEngine(): ContextEngine
        fun ruleExecutor(): AutomationExecutor
    }

    private companion object {
        const val TAG = "JarvisAutomation"
    }
}
