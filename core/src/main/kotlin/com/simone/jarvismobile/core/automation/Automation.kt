package com.simone.jarvismobile.core.automation

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * When an automation fires.
 *
 * The set is deliberately small and all of it works offline with no background
 * microphone and no reading of other apps' data. Time and power triggers need
 * no extra permission at all. [ArrivedAt] is the one exception: it needs the
 * location permission, and it earns it by staying inside a single, explicit,
 * user-created geofence — the OS evaluates arrival on-device, there is no
 * continuous tracking and no network (see ADR 0011 and docs/PRIVACY.md).
 */
sealed interface Trigger {

    /** Every selected weekday at [time]. An empty day set means every day. */
    data class TimeOfDay(val time: LocalTime, val days: Set<DayOfWeek> = emptySet()) : Trigger {
        fun matches(day: DayOfWeek): Boolean = days.isEmpty() || day in days
    }

    /** The battery has fallen to [percent] or below. */
    data class BatteryBelow(val percent: Int) : Trigger {
        init { require(percent in 1..99) }
    }

    /** The charger has just been plugged in. */
    data object ChargingStarted : Trigger

    /**
     * The phone has entered the saved place named [place].
     *
     * The name is a reference into the user's own list of places
     * (`JARVIS/Luoghi.md`); the coordinates live there, not in the rule, so a
     * rule reads "arrivo a casa" and the place can be moved without rewriting
     * every rule. A rule whose place has no coordinates yet is kept but stays
     * dormant — it cannot arm a geofence until the place is defined.
     */
    data class ArrivedAt(val place: String) : Trigger
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

    /** The words the action carries, whatever the action calls them. */
    val payload: String
        get() = when (this) {
            is Notify -> message
            is Speak -> message
            is AddAgenda -> text
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
