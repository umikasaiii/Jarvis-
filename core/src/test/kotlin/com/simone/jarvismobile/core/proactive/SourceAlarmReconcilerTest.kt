package com.simone.jarvismobile.core.proactive

import kotlin.test.assertEquals
import kotlin.test.Test

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §9/§19 (S02/S03). Pins the exact 08:30-source/+5-offset
 * scenario the spec calls out, plus every branch of the reconciliation rule.
 */
class SourceAlarmReconcilerTest {

    private val offsetMs = 5 * 60_000L

    @Test
    fun `no existing plan, future source observed - schedules from it`() {
        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = null,
            previousFireAtMs = null,
            newlyObservedSourceAlarmAtMs = 1_000_000L,
            nowMs = 500_000L,
            offsetMs = offsetMs,
        )
        assertEquals(SourceAlarmReconciler.Decision.Schedule(1_000_000L + offsetMs, 1_000_000L), decision)
    }

    @Test
    fun `future source unchanged from existing plan - keeps existing, no reschedule`() {
        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = 1_000_000L,
            previousFireAtMs = 1_000_000L + offsetMs,
            newlyObservedSourceAlarmAtMs = 1_000_000L,
            nowMs = 500_000L,
            offsetMs = offsetMs,
        )
        assertEquals(SourceAlarmReconciler.Decision.KeepExisting, decision)
    }

    @Test
    fun `BEFORE source time, a reliable edit to a different future source revises the pending slot`() {
        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = 1_000_000L,
            previousFireAtMs = 1_000_000L + offsetMs,
            newlyObservedSourceAlarmAtMs = 2_000_000L,
            nowMs = 500_000L,
            offsetMs = offsetMs,
        )
        assertEquals(SourceAlarmReconciler.Decision.Schedule(2_000_000L + offsetMs, 2_000_000L), decision)
    }

    @Test
    fun `BEFORE source time, a genuine cancel (null observation) cancels the pending slot`() {
        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = 1_000_000L,
            previousFireAtMs = 1_000_000L + offsetMs,
            newlyObservedSourceAlarmAtMs = null,
            nowMs = 500_000L,
            offsetMs = offsetMs,
        )
        assertEquals(SourceAlarmReconciler.Decision.NoSource, decision)
    }

    @Test
    fun `08_30 source plus 5 offset - AT maturity a null observation must NOT cancel today's matured occurrence`() {
        val sourceAt = 1_000_000L // "08:30"
        val fireAt = sourceAt + offsetMs // "08:35"
        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = sourceAt,
            previousFireAtMs = fireAt,
            newlyObservedSourceAlarmAtMs = null, // the alarm cleared/dismissed at maturity
            nowMs = sourceAt, // exactly at maturity
            offsetMs = offsetMs,
        )
        assertEquals(SourceAlarmReconciler.Decision.KeepExisting, decision)
    }

    @Test
    fun `AFTER maturity, a newly observed tomorrow alarm must NOT cancel today's matured occurrence`() {
        val sourceAt = 1_000_000L
        val fireAt = sourceAt + offsetMs
        val tomorrowsAlarm = sourceAt + 86_400_000L
        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = sourceAt,
            previousFireAtMs = fireAt,
            newlyObservedSourceAlarmAtMs = tomorrowsAlarm,
            nowMs = sourceAt + 60_000L, // one minute after maturity
            offsetMs = offsetMs,
        )
        assertEquals(SourceAlarmReconciler.Decision.KeepExisting, decision)
    }

    @Test
    fun `no existing plan and the newly observed source has already passed - no-op, never scheduled from a stale reading`() {
        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = null,
            previousFireAtMs = null,
            newlyObservedSourceAlarmAtMs = 400_000L,
            nowMs = 500_000L,
            offsetMs = offsetMs,
        )
        assertEquals(SourceAlarmReconciler.Decision.NoOp, decision)
    }

    @Test
    fun `existing plan present and the newly observed source has already passed - keeps existing rather than acting on a stale reading`() {
        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = 1_000_000L,
            previousFireAtMs = 1_000_000L + offsetMs,
            newlyObservedSourceAlarmAtMs = 400_000L,
            nowMs = 500_000L,
            offsetMs = offsetMs,
        )
        assertEquals(SourceAlarmReconciler.Decision.KeepExisting, decision)
    }

    @Test
    fun `no existing plan, no source observed - NoSource, never a crash or a fabricated schedule`() {
        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = null,
            previousFireAtMs = null,
            newlyObservedSourceAlarmAtMs = null,
            nowMs = 500_000L,
            offsetMs = offsetMs,
        )
        assertEquals(SourceAlarmReconciler.Decision.NoSource, decision)
    }
}
