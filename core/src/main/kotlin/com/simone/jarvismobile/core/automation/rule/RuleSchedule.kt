package com.simone.jarvismobile.core.automation.rule

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When a time-based trigger should next fire (§9, phase 5).
 *
 * This is the pure half of the scheduler: given a [TriggerSpec] and the current
 * moment, it says the next wall-clock instant the trigger is due, or null when it
 * is not a scheduled kind or has nothing left to fire. The app layer takes that
 * instant and books one exact alarm; keeping the arithmetic here means the awkward
 * cases — a daily rule whose time has already passed today, a one-off in the past,
 * a malformed parameter — are unit-tested rather than only reasoned about.
 *
 * Only clock triggers live here. Place, Bluetooth, activity and the rest are
 * delivered by their own sources when they happen, not booked in advance.
 */
object RuleSchedule {

    /** Trigger kinds this scheduler books in advance. */
    val SCHEDULED_TYPES: Set<String> = setOf(
        TriggerRegistry.TIME_AT,
        TriggerRegistry.RECURRING_TIME,
    )

    fun isScheduled(type: String): Boolean = type in SCHEDULED_TYPES

    /** True when the rule has at least one trigger this scheduler can book. */
    fun hasScheduledTrigger(rule: AutomationRule): Boolean =
        rule.triggers.any { isScheduled(it.type) }

    /**
     * The next instant [spec] is due after [now], or null when it is not a
     * scheduled kind, is malformed, or (for a one-off) already in the past.
     */
    fun nextOccurrence(spec: TriggerSpec, now: LocalDateTime): LocalDateTime? = when (spec.type) {
        TriggerRegistry.TIME_AT -> timeAt(spec, now)
        TriggerRegistry.RECURRING_TIME -> recurring(spec, now)
        else -> null
    }

    /**
     * The earliest next occurrence across all of a rule's scheduled triggers,
     * paired with the trigger that owns it — the app books exactly this one, and
     * re-books after it fires.
     */
    fun nextForRule(rule: AutomationRule, now: LocalDateTime): Pair<TriggerSpec, LocalDateTime>? =
        rule.triggers
            .filter { isScheduled(it.type) }
            .mapNotNull { spec -> nextOccurrence(spec, now)?.let { spec to it } }
            .minByOrNull { it.second }

    // --- per-kind ---------------------------------------------------------

    /** A precise date-time fires once, and only if it is still ahead of us. */
    private fun timeAt(spec: TriggerSpec, now: LocalDateTime): LocalDateTime? {
        val at = spec.param("at")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?: return null
        return at.takeIf { it.isAfter(now) }
    }

    /**
     * A daily rule fires today if its time is still to come and today is a
     * selected day, otherwise on the first selected day after that. An empty or
     * absent day set means every day.
     */
    private fun recurring(spec: TriggerSpec, now: LocalDateTime): LocalDateTime? {
        val time = spec.param("time")?.let { parseTime(it) } ?: return null
        val days = parseDays(spec.param("days"))
        var candidate = now.toLocalDate().atTime(time)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        // Seven would be enough for a non-empty set; eight is a cheap margin.
        repeat(8) {
            if (days.isEmpty() || candidate.dayOfWeek in days) return candidate
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    // --- parameter parsing ------------------------------------------------

    /** "8:00", "08:00" and "8.30" all parse; anything else is null, not a guess. */
    fun parseTime(value: String): LocalTime? {
        val parts = value.trim().split(':', '.')
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /**
     * Days are comma-separated ISO-8601 numbers, 1=Monday … 7=Sunday
     * (e.g. "1,3,5"). Blank or absent means every day. Unknown numbers are
     * ignored rather than failing the whole set — a stale extra day should not
     * silence a rule.
     */
    fun parseDays(value: String?): Set<DayOfWeek> {
        if (value.isNullOrBlank()) return emptySet()
        return value.split(',')
            .mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n in 1..7 } }
            .map { DayOfWeek.of(it) }
            .toSet()
    }
}
