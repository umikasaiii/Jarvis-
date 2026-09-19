package com.simone.jarvismobile.core.proactive

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §13 (MORNING WINDOW / LOGICAL DATE). Master v1.5 records no
 * existing product decision for an explicit upper bound (only
 * `MORNING_EARLIEST_HOUR = 5`, in `ProactiveManager`), so this adopts the
 * Astra audit's own stated default closure target — 05:00 inclusive, 12:00
 * exclusive — as an internal scheduling constant, never a new user-facing
 * setting (§13: "STOP and report before inventing one" only applies when a
 * conflicting decision already exists; none does here).
 */
object MorningWindowPolicy {
    const val EARLIEST_HOUR = 5
    const val LATEST_HOUR_EXCLUSIVE = 12

    /** Whether [hour] (0-23, local time) falls inside the supported morning delivery window. */
    fun isWithinWindow(hour: Int): Boolean = hour in EARLIEST_HOUR until LATEST_HOUR_EXCLUSIVE
}
