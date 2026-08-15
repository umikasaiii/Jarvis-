package com.simone.jarvismobile.core.automation.rule

import com.simone.jarvismobile.core.automation.Action
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.core.automation.Trigger
import java.time.LocalDateTime

/**
 * Carries rules from the original single-trigger [Automation] model into the
 * generic [AutomationRule] one.
 *
 * The conversion is deliberately conservative: only triggers with an exact
 * counterpart are translated, and anything else returns null so the caller can
 * report it. Approximating would be worse than not migrating — a rule that fires
 * at a different time, or on a different event, than the user set is a bug the
 * user has no way to see until it misfires.
 *
 * Lives in `:core` rather than next to the repository precisely so this can be
 * unit-tested: a migration that runs once, silently, on real user data is the
 * last place to rely on "it compiled".
 */
object LegacyRuleConverter {

    /** Converts [old], or returns null when no faithful equivalent exists. */
    fun convert(old: Automation, now: LocalDateTime): AutomationRule? {
        val trigger = convertTrigger(old.trigger) ?: return null
        val action = convertAction(old.action)
        return AutomationRule(
            id = old.id.ifBlank { "legacy-" + old.name.hashCode().toUInt().toString(16) },
            name = old.name,
            enabled = old.enabled,
            triggers = listOf(trigger),
            actions = listOf(action),
            // A one-off legacy trigger stays one-off in the new model.
            oneShot = old.trigger is Trigger.Once,
            lastFiredAt = old.lastFired,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Why [old] could not be converted, for a report the user can act on. */
    fun reasonForSkipping(old: Automation): String? {
        if (convertTrigger(old.trigger) != null) return null
        return "il trigger «${describeTrigger(old.trigger)}» non ha ancora un equivalente nel nuovo motore"
    }

    private fun convertTrigger(trigger: Trigger): TriggerSpec? = when (trigger) {
        is Trigger.TimeOfDay -> TriggerSpec(
            TriggerRegistry.RECURRING_TIME,
            buildMap {
                put("time", trigger.time.toString())
                if (trigger.days.isNotEmpty()) {
                    put("days", trigger.days.sorted().joinToString(",") { it.name })
                }
            },
        )

        is Trigger.Once -> TriggerSpec(TriggerRegistry.TIME_AT, mapOf("at" to trigger.at.toString()))

        Trigger.ChargingStarted -> TriggerSpec(TriggerRegistry.DEVICE_CHARGING)

        is Trigger.MorningUnlock -> TriggerSpec(
            TriggerRegistry.FIRST_UNLOCK_OF_DAY,
            mapOf("after" to trigger.after.toString()),
        )

        is Trigger.BluetoothConnected -> TriggerSpec(
            TriggerRegistry.BLUETOOTH_CONNECTED,
            mapOf("device" to trigger.device),
        )

        // No faithful equivalent yet. Notably Trigger.ArrivedHome: the old model
        // could express it, but no geofencing exists in the app, so it never
        // fired. Migrating it would just move a rule that cannot work.
        is Trigger.BatteryBelow,
        Trigger.ScreenUnlocked,
        Trigger.HeadphonesConnected,
        is Trigger.AirplaneMode,
        is Trigger.WifiPower,
        is Trigger.MobileData,
        is Trigger.WifiNetwork,
        Trigger.ArrivedHome,
        -> null
    }

    /** Every legacy action has an exact counterpart, so this cannot fail. */
    private fun convertAction(action: Action): ActionSpec = when (action) {
        is Action.Notify -> ActionSpec(ActionRegistry.SHOW_NOTIFICATION, mapOf("message" to action.message))
        is Action.Speak -> ActionSpec(ActionRegistry.SPEAK, mapOf("message" to action.message))
        is Action.AddAgenda -> ActionSpec(ActionRegistry.CREATE_REMINDER, mapOf("text" to action.text))
        is Action.Tool -> ActionSpec(ActionRegistry.RUN_TOOL, mapOf("command" to action.command))
    }

    private fun describeTrigger(trigger: Trigger): String = when (trigger) {
        is Trigger.BatteryBelow -> "batteria sotto il ${trigger.percent}%"
        Trigger.ScreenUnlocked -> "sblocco schermo"
        Trigger.HeadphonesConnected -> "cuffie collegate"
        is Trigger.AirplaneMode -> "modalità aereo"
        is Trigger.WifiPower -> "Wi-Fi acceso/spento"
        is Trigger.MobileData -> "dati mobili"
        is Trigger.WifiNetwork -> "rete Wi-Fi ${trigger.ssid}"
        Trigger.ArrivedHome -> "arrivo a casa"
        else -> trigger::class.simpleName.orEmpty()
    }
}
