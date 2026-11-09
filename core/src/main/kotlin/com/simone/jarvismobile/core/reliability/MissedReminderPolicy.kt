package com.simone.jarvismobile.core.reliability

import java.time.Duration
import java.time.LocalDateTime

/** A reminder alert whose trigger time is known, identified by a stable key. */
data class ReminderCandidate(val key: String, val trigger: LocalDateTime)

/**
 * Reminders whose time passed while the phone was off (or the app was force-stopped) used to be dropped silently when
 * alarms were re-armed: only triggers *after* "now" were booked. A medicine reminder for 08:00 on a phone that booted
 * at 08:10 simply never existed.
 *
 * This picks the ones worth delivering late: not yet delivered, missed no more than [window] ago, at most [max] of
 * them (oldest dropped first) so a long outage never produces a flood of notifications.
 */
object MissedReminderPolicy {
    val DEFAULT_WINDOW: Duration = Duration.ofHours(12)
    const val DEFAULT_MAX = 5

    fun select(
        candidates: List<ReminderCandidate>,
        delivered: Set<String>,
        now: LocalDateTime,
        window: Duration = DEFAULT_WINDOW,
        max: Int = DEFAULT_MAX,
    ): List<ReminderCandidate> =
        candidates
            .filter { it.key !in delivered }
            .filter { !it.trigger.isAfter(now) && !it.trigger.isBefore(now.minus(window)) }
            .sortedByDescending { it.trigger }
            .take(max)
            .sortedBy { it.trigger }

    /** Keeps the delivered-set bounded: forgets keys that are no longer candidates. */
    fun prune(delivered: Set<String>, stillKnown: Set<String>): Set<String> = delivered.intersect(stillKnown)
}
