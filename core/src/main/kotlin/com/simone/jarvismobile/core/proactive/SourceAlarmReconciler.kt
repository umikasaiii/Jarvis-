package com.simone.jarvismobile.core.proactive

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §9 (NEXT_ALARM SOURCE PRESERVATION, marked critical).
 *
 * Example the spec itself gives: source alarm = 08:30, offset = +5, planned
 * briefing = 08:35. At 08:30 the system `AlarmManager.nextAlarmClock` may
 * roll to `null` or to tomorrow's alarm — that MUST NOT erase the already
 * matured 08:35 occurrence. This is the pure decision at the heart of that
 * rule, kept entirely free of Android so it is actually unit-testable:
 *
 *  - BEFORE the source time, a reliable source edit/cancel may revise or
 *    cancel the pending slot (the user changed or cleared their alarm).
 *  - AT/AFTER the source time, the already-valid offset occurrence is
 *    frozen — a `null` or a different (e.g. tomorrow's) observation from
 *    then on is never allowed to cancel it.
 *
 * Multi-day continuity is handled by the caller keying the previous plan to
 * the current logical date (a plan from yesterday is never "previous" for
 * today's reconciliation) — this object only decides what to do given
 * whatever previous state the caller considers still relevant today.
 */
object SourceAlarmReconciler {

    sealed class Decision {
        /** No source alarm is exposed/known right now — nothing to schedule, nothing to cancel. */
        data object NoSource : Decision()

        /** Book (or rebook) an exact alarm at [fireAtMs], derived from [sourceAlarmAtMs]. */
        data class Schedule(val fireAtMs: Long, val sourceAlarmAtMs: Long) : Decision()

        /** An already-scheduled, already-valid offset occurrence — do not touch it. */
        data object KeepExisting : Decision()

        /** No existing plan, and the newly observed source has already passed — nothing to do. */
        data object NoOp : Decision()
    }

    /**
     * @param previousSourceAlarmAtMs the source alarm instant the currently
     *   scheduled offset occurrence (if any, and if still relevant today)
     *   was derived from, or `null` if none.
     * @param previousFireAtMs the fire instant of that currently scheduled
     *   offset occurrence, or `null`.
     * @param newlyObservedSourceAlarmAtMs what `AlarmManager.nextAlarmClock()`
     *   reports right now, or `null` (no alarm exposed / cleared / dismissed).
     * @param nowMs the current instant.
     * @param offsetMs the configured offset applied after the source alarm.
     */
    fun reconcile(
        previousSourceAlarmAtMs: Long?,
        previousFireAtMs: Long?,
        newlyObservedSourceAlarmAtMs: Long?,
        nowMs: Long,
        offsetMs: Long,
    ): Decision {
        val hasExisting = previousSourceAlarmAtMs != null && previousFireAtMs != null
        val existingMatured = hasExisting && nowMs >= previousSourceAlarmAtMs!!

        if (existingMatured) {
            // AT/AFTER the source time: freeze it, unconditionally — a later
            // null/changed observation must never cancel an already-valid,
            // not-yet-fired offset occurrence.
            return Decision.KeepExisting
        }

        if (newlyObservedSourceAlarmAtMs == null) {
            // No source currently exposed. BEFORE the (not-yet-matured, or
            // nonexistent) source time, this is a genuine cancel/absence.
            return Decision.NoSource
        }

        if (newlyObservedSourceAlarmAtMs <= nowMs) {
            // A stale reading (already in the past) — never schedule from it.
            return if (hasExisting) Decision.KeepExisting else Decision.NoOp
        }

        if (!hasExisting || previousSourceAlarmAtMs != newlyObservedSourceAlarmAtMs) {
            // Either no plan exists yet, or (BEFORE maturity) a reliable edit
            // changed which future source this should be derived from.
            return Decision.Schedule(
                fireAtMs = newlyObservedSourceAlarmAtMs + offsetMs,
                sourceAlarmAtMs = newlyObservedSourceAlarmAtMs,
            )
        }

        // Same future source, already scheduled identically.
        return Decision.KeepExisting
    }
}
