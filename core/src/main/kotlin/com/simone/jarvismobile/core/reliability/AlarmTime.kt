package com.simone.jarvismobile.core.reliability

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * BETA 7 — converts the wall-clock time a user chose ("07:30") into the epoch instant an alarm is booked for.
 *
 * Alarms are stored as absolute instants, so they must be recomputed whenever the zone rules or the clock change
 * ([RearmTrigger.TIMEZONE_CHANGED] / [RearmTrigger.TIME_CHANGED]). Pure and parameterised by [ZoneId] so the
 * daylight-saving cases can be tested without touching the machine clock.
 *
 * - A time that does not exist (spring forward gap, e.g. 02:30 on the switch day) resolves to the first instant after
 *   the gap — the same result `java.time` gives, pinned here by tests so it cannot silently change.
 * - A time that exists twice (autumn overlap) resolves to the earlier instant, so the alarm never fires an hour late.
 */
object AlarmTime {
    fun epochMs(at: LocalDateTime, zone: ZoneId): Long = at.atZone(zone).toInstant().toEpochMilli()

    /** The wall-clock time an already-booked instant corresponds to in [zone] — what the user would read on the clock. */
    fun wallClock(epochMs: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)

    /** True when a booked instant no longer matches the wall-clock time it was meant for (zone or clock change). */
    fun isStale(bookedEpochMs: Long, intended: LocalDateTime, zone: ZoneId): Boolean =
        bookedEpochMs != epochMs(intended, zone)
}
