package com.simone.jarvismobile.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 3 (Session Epoch + Reset +
 * Stale Callback Safety), findings JARVIS-07/JARVIS-16. Pins the pure
 * mechanics [ConversationManager][com.simone.jarvismobile.engine.ConversationManager]
 * (`app/`) and [ConversationalJarvisEngine][com.simone.jarvismobile.engine.ConversationalJarvisEngine]
 * (`app/`) both build on — see those classes' own doc comments for the real
 * integration, not directly unit-testable here (both need Android/Hilt
 * dependencies this environment has no Robolectric for, same established
 * limit as every other `app/`-side class in this project).
 */
class SessionEpochTest {

    // § test 1 — reset increments/invalidates epoch.
    @Test
    fun `invalidate bumps the epoch, monotonically`() {
        val epoch = SessionEpoch()
        val first = epoch.current()
        epoch.invalidate()
        val second = epoch.current()
        assertNotEquals(first, second)
        epoch.invalidate()
        val third = epoch.current()
        assertNotEquals(second, third)
    }

    @Test
    fun `current epoch is always current`() {
        val epoch = SessionEpoch()
        assertTrue(epoch.isCurrent(epoch.current()))
        epoch.invalidate()
        assertTrue(epoch.isCurrent(epoch.current()))
    }

    // § test 2/6 — a captured-then-superseded epoch is no longer current;
    // simulates "old async result cannot update new session" / "callback
    // arriving after cancellation/reset is discarded" at the pure-mechanics
    // level a real turn/job would check against.
    @Test
    fun `an epoch captured before invalidate reads as stale afterwards`() {
        val epoch = SessionEpoch()
        val capturedByOldTurn = epoch.current()
        epoch.invalidate() // a reset happens while the old turn's job is still in flight
        assertFalse(epoch.isCurrent(capturedByOldTurn))
    }

    // § test 11 — a normal same-epoch turn still commits normally.
    @Test
    fun `an epoch captured and checked with no reset in between stays current`() {
        val epoch = SessionEpoch()
        val capturedByTurn = epoch.current()
        // ... turn does its work, no reset happens ...
        assertTrue(epoch.isCurrent(capturedByTurn))
    }

    @Test
    fun `multiple in-flight turns each keep their own captured epoch, only the newest is current`() {
        val epoch = SessionEpoch()
        val turnA = epoch.current()
        epoch.invalidate()
        val turnB = epoch.current()
        epoch.invalidate()
        val turnC = epoch.current()

        assertFalse(epoch.isCurrent(turnA))
        assertFalse(epoch.isCurrent(turnB))
        assertTrue(epoch.isCurrent(turnC))
    }

    // § deferred/fake async work, reproducing the race deterministically
    // (no sleeps) — a "job" here is just a captured epoch value plus a
    // deferred commit function, exactly mirroring how ConversationManager's
    // own mutators are gated in the real app/ code (see its doc comment).
    private class FakeSession {
        val epoch = SessionEpoch()
        var committedValue: String? = null
        var commitAttempts = 0

        fun startTurn(): Long = epoch.current()

        /** Mirrors ConversationManager's mutator shape: `if (!isEpochCurrent(epoch)) return`. */
        fun commitIfCurrent(capturedEpoch: Long, value: String) {
            commitAttempts++
            if (!epoch.isCurrent(capturedEpoch)) return
            committedValue = value
        }

        fun reset() {
            epoch.invalidate()
            committedValue = null
        }
    }

    @Test
    fun `a late-arriving commit from a turn superseded by reset never lands`() {
        val session = FakeSession()
        val staleTurnEpoch = session.startTurn() // turn A begins

        // The user resets ("Nuova conversazione") WHILE turn A's native call
        // is still blocked/ignoring cancellation — exactly the race §6/§7
        // describe: cancel requested != work physically stopped.
        session.reset()

        // Turn A's blocking call finally returns and tries to commit late.
        session.commitIfCurrent(staleTurnEpoch, "stale answer from turn A")

        assertEquals(1, session.commitAttempts)
        assertEquals(null, session.committedValue) // never landed
    }

    @Test
    fun `a same-epoch commit lands normally when no reset occurred`() {
        val session = FakeSession()
        val turnEpoch = session.startTurn()
        session.commitIfCurrent(turnEpoch, "real answer")
        assertEquals("real answer", session.committedValue)
    }

    @Test
    fun `after reset, a NEW turn's commit lands even though an OLD turn is still pending`() {
        val session = FakeSession()
        val oldTurnEpoch = session.startTurn()
        session.reset()
        val newTurnEpoch = session.startTurn()

        // New turn commits first (normal case).
        session.commitIfCurrent(newTurnEpoch, "new turn's answer")
        assertEquals("new turn's answer", session.committedValue)

        // Old turn's late callback arrives afterwards and must not overwrite it.
        session.commitIfCurrent(oldTurnEpoch, "old turn's stale answer")
        assertEquals("new turn's answer", session.committedValue)
    }

    @Test
    fun `EpochScoped carries the epoch it was stamped with, independent of later invalidation`() {
        val epoch = SessionEpoch()
        val stampedNow = EpochScoped("pending value", epoch.current())
        assertTrue(epoch.isCurrent(stampedNow.epoch))
        epoch.invalidate()
        // The wrapper's own field never changes — staleness is entirely a
        // property of comparing it against the (now-different) live epoch.
        assertEquals(0L, stampedNow.epoch)
        assertFalse(epoch.isCurrent(stampedNow.epoch))
    }
}
