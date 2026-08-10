package com.simone.jarvismobile.core.proactive

import java.time.LocalDate

/**
 * Turns the everyday [ProactiveSnapshot] into candidate suggestions. The *content*
 * comes deterministically from the data (the agenda, the battery, the alarm) —
 * nothing is invented here. The Android layer may later re-phrase a message more
 * naturally with the local model, but the facts are fixed by these functions, so
 * a proactive line can never say something that isn't in the user's own data.
 */
object ProactiveComposer {

    /** Battery threshold under which a "charge before your early alarm" is worth it. */
    const val LOW_BATTERY = 30

    /** An alarm at or before this hour counts as "early", worth a heads-up the night before. */
    const val EARLY_HOUR = 8

    /**
     * "Buongiorno" plus today's appointments and tasks; just "Buongiorno" when the
     * day is clear. Meant to be offered at the first morning unlock.
     */
    fun morningDigest(snapshot: ProactiveSnapshot, today: LocalDate): ProactiveSuggestion {
        val items = snapshot.todayAppointments + snapshot.todayTasks
        val message = if (items.isEmpty()) {
            "Buongiorno."
        } else {
            "Buongiorno. Oggi: " + items.joinToString(", ") + "."
        }
        return ProactiveSuggestion(
            kind = ProactiveKind.MORNING_DIGEST,
            message = message,
            priority = 50,
            dedupKey = "${ProactiveKind.MORNING_DIGEST}:$today",
        )
    }

    /**
     * Suggests charging when the battery is low, the phone isn't already charging,
     * and there is an early alarm the next morning. Returns null when it wouldn't
     * be useful, so the governor simply has nothing to deliver.
     */
    fun batteryBeforeAlarm(snapshot: ProactiveSnapshot, today: LocalDate): ProactiveSuggestion? {
        if (snapshot.charging) return null
        if (snapshot.batteryPercent !in 0..LOW_BATTERY) return null
        val alarm = snapshot.nextAlarm ?: return null
        if (alarm.hour > EARLY_HOUR) return null
        val clock = "%02d:%02d".format(alarm.hour, alarm.minute)
        return ProactiveSuggestion(
            kind = ProactiveKind.BATTERY_BEFORE_ALARM,
            message = "Batteria al ${snapshot.batteryPercent}% e domani sveglia alle $clock: " +
                "ti conviene metterla in carica stanotte.",
            priority = 80,
            dedupKey = "${ProactiveKind.BATTERY_BEFORE_ALARM}:$today",
        )
    }

    /**
     * An evening recap of tomorrow-relevant items. Only offered when there is
     * something to say; otherwise null.
     */
    fun eveningDigest(snapshot: ProactiveSnapshot, today: LocalDate): ProactiveSuggestion? {
        val items = snapshot.todayAppointments + snapshot.todayTasks
        if (items.isEmpty()) return null
        return ProactiveSuggestion(
            kind = ProactiveKind.EVENING_DIGEST,
            message = "Per domani: " + items.joinToString(", ") + ".",
            priority = 40,
            dedupKey = "${ProactiveKind.EVENING_DIGEST}:$today",
        )
    }

    /** Convenience for the caller: all non-null candidates for [now]'s date. */
    fun candidates(snapshot: ProactiveSnapshot, today: LocalDate): List<ProactiveSuggestion> =
        listOfNotNull(
            morningDigest(snapshot, today),
            batteryBeforeAlarm(snapshot, today),
            eveningDigest(snapshot, today),
        )
}
