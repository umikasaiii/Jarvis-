package com.simone.jarvismobile.core.proactive

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §4 (typed trigger contract). Replaces the old pattern of
 * threading a bare `String`/`isRealUnlock: Boolean` pair through
 * `ProactiveManager` — a configured-time alarm was never labelled as an
 * unlock, and NEXT_ALARM was never proof the user woke up, but the old
 * signature made exactly that mistake possible (`evaluateOnUnlock(...,
 * isRealUnlock = true)` hardcoded for every source that called it). Only
 * [FIRST_UNLOCK] is a real `ACTION_USER_PRESENT` observation.
 */
enum class ProactiveTriggerSource {
    FIRST_UNLOCK,
    NEXT_ALARM,
    CONFIGURED_TIME,
    PERIODIC_FALLBACK,
    MANUAL_DEBUG,
}

/** § §10 — source-availability honesty for the NEXT_ALARM signal. Never fabricated, never inferred from notification text. */
enum class NextAlarmSourceAvailability {
    SOURCE_EXPOSED,
    SOURCE_NOT_EXPOSED,
    OBSERVER_UNAVAILABLE,
    NO_SOURCE_ALARM,
    SOURCE_READ_FAILED,
}

/** § §14 — how a scheduling attempt actually landed; never collapsed into a boolean. */
enum class ScheduleExactness {
    EXACT,
    DEGRADED_INEXACT,
    NOT_SCHEDULED,
}
