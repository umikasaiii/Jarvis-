package com.simone.jarvismobile.core.automation

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * When an automation fires.
 *
 * Everything here works offline with no background microphone. The time and
 * power triggers need no extra permission; the device-event triggers below
 * (airplane/Wi-Fi/data/headphones/unlock) are delivered only to a live process,
 * so they fire through an opt-in foreground "Automazioni attive" service, not a
 * hidden background listener. The two location-based ones ([WifiNetwork] reading
 * the SSID, [ArrivedHome]) additionally need the Location permission and are
 * opt-in, off by default (docs/PRIVACY.md).
 */
sealed interface Trigger {

    /** Every selected weekday at [time]. An empty day set means every day. */
    data class TimeOfDay(val time: LocalTime, val days: Set<DayOfWeek> = emptySet()) : Trigger {
        fun matches(day: DayOfWeek): Boolean = days.isEmpty() || day in days
    }

    /**
     * A single moment in the future. Fires once and is then spent — the rule
     * keeps its [lastFired] mark so it is never rescheduled. This is what turns
     * "alle 11.45 accendi la torcia" from an action that runs *now* into a rule
     * that runs *then*.
     */
    data class Once(val at: LocalDateTime) : Trigger

    /** The battery has fallen to [percent] or below. */
    data class BatteryBelow(val percent: Int) : Trigger {
        init { require(percent in 1..99) }
    }

    /** The charger has just been plugged in. */
    data object ChargingStarted : Trigger

    /** The first screen unlock of the day after [after] (a controlled "buongiorno"). */
    data class MorningUnlock(val after: LocalTime) : Trigger

    /** Any screen unlock (device present). */
    data object ScreenUnlocked : Trigger

    /** A wired or Bluetooth headset has just connected. */
    data object HeadphonesConnected : Trigger

    /** A named Bluetooth device has just connected. */
    data class BluetoothConnected(val device: String) : Trigger

    /** Airplane mode has just been turned [on] or off. */
    data class AirplaneMode(val on: Boolean) : Trigger

    /** Wi-Fi radio has just been turned [on] or off. */
    data class WifiPower(val on: Boolean) : Trigger

    /** Mobile data has just been turned [on] or off (best-effort on some ROMs). */
    data class MobileData(val on: Boolean) : Trigger

    /** Connected to a specific Wi-Fi network. Reading the [ssid] needs Location. */
    data class WifiNetwork(val ssid: String) : Trigger

    /** Arrived at the saved home location (geofence). Needs Location. */
    data object ArrivedHome : Trigger
}

/**
 * What an automation does.
 *
 * Each action maps onto something JARVIS already does under user control. There
 * is no "run arbitrary intent" and no shell: an automation can only reach the
 * same allowlisted surface a typed command can (ADR 0008).
 */
sealed interface Action {
    /** A private local notification. */
    data class Notify(val message: String) : Action

    /** Said out loud, subject to the usual spoken-reply setting. */
    data class Speak(val message: String) : Action

    /** Files a dated item in JARVIS's own agenda. */
    data class AddAgenda(val text: String) : Action

    /**
     * Runs an allowlisted device command — the very same words a typed command
     * would use. Only the text is stored; nothing is executed until the trigger
     * is due, and even then the words must still map to a permitted tool through
     * the existing CommandMatcher. There is no way to smuggle an arbitrary tool
     * in here: an unrecognised [command] simply does nothing (ADR 0008).
     */
    data class Tool(val command: String) : Action

    /** The words the action carries, whatever the action calls them. */
    val payload: String
        get() = when (this) {
            is Notify -> message
            is Speak -> message
            is AddAgenda -> text
            is Tool -> command
        }
}

/**
 * One user-defined rule: when [trigger] happens, do [action].
 *
 * Stored as a Markdown line in the vault like everything else JARVIS owns, so
 * the user can read and edit their automations in Obsidian rather than only
 * through the app (see the vault rule in CLAUDE.md).
 */
data class Automation(
    val id: String = newId(),
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val action: Action,
    /** Last time this fired, so a daily rule does not fire twice in one day. */
    val lastFired: LocalDateTime? = null,
) {
    fun toMarkdown(): String = buildString {
        append("- [").append(if (enabled) 'x' else ' ').append("] ")
        append(AutomationCodec.renderTrigger(trigger))
        append(" — ")
        append(AutomationCodec.renderAction(action))
        append(" {#").append(id)
        lastFired?.let { append(" @").append(it) }
        append('}')
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)
    }
}
