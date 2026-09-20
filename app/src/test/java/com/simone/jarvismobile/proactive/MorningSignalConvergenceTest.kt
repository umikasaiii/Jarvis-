package com.simone.jarvismobile.proactive

import com.simone.jarvismobile.core.proactive.OccurrenceClaimOutcome
import com.simone.jarvismobile.core.proactive.ProactiveOccurrenceState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE E §4. The REQUIRED INVARIANT stated explicitly by the spec:
 * "ALL MORNING SIGNALS FOR LOGICAL DATE D converge on MORNING_DIGEST:D, at
 * most one production dispatch side effect" — proven here as ONE explicit,
 * named integration suite across every documented source-order permutation
 * (A-E), rather than left implicit across the many finer-grained
 * [ProactiveOccurrenceStoreTest] cases that already cover individual
 * pairs/transitions.
 *
 * Uses the SAME [ProactiveOccurrenceStore]/[FakeProactiveOccurrenceDao] the
 * rest of Work Package A/14.1's suite uses (plain JVM, no Robolectric) —
 * this is the real production claim/deliver machinery
 * [com.simone.jarvismobile.proactive.ProactiveManager.run] calls for
 * MORNING_DIGEST, exercised end-to-end (claim -> deliveryAttempt ->
 * delivered) rather than only its individual transition methods in
 * isolation.
 *
 * "Source" here means [com.simone.jarvismobile.core.proactive
 * .ProactiveTriggerSource]'s four values, referenced by name (the store
 * itself is source-agnostic — it only ever sees a `triggerSource: String`
 * — so no import of that enum is needed to prove the invariant holds for
 * all four).
 */
class MorningSignalConvergenceTest {

    private val date = LocalDate.of(2026, 9, 20)
    private val key = "MORNING_DIGEST:$date"

    /** claim -> deliveryAttempt -> delivered, mirroring [ProactiveManager.run]'s Deliver branch via [ProactiveDeliveryDispatcher]. */
    private suspend fun deliverVia(store: ProactiveOccurrenceStore, triggerSource: String, now: Long): OccurrenceClaimOutcome {
        val outcome = store.claim(key, "MORNING_DIGEST", date, triggerSource, now)
        if (outcome is OccurrenceClaimOutcome.Claimed || outcome is OccurrenceClaimOutcome.TakeoverAllowed) {
            assertTrue("$triggerSource's deliveryAttempt fencing must not be lost right after a winning claim", store.markDeliveryAttempt(key, now))
            assertTrue("$triggerSource's delivered fencing must not be lost right after its own deliveryAttempt", store.markDelivered(key, now))
        }
        return outcome
    }

    /** A later source's attempt must be a pure no-op loser: AlreadyOwned(DELIVERED), and the row must not change at all. */
    private suspend fun assertSuppressed(store: ProactiveOccurrenceStore, dao: FakeProactiveOccurrenceDao, triggerSource: String, now: Long) {
        val before = dao.rowOrNull(key)!!.copy()
        val outcome = store.claim(key, "MORNING_DIGEST", date, triggerSource, now)
        assertEquals(
            "$triggerSource must see the occurrence as already DELIVERED, never re-claimable",
            OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED),
            outcome,
        )
        assertEquals("$triggerSource's suppressed attempt must perform NO side effect on the row", before, dao.rowOrNull(key))
        // A suppressed source must never be able to (re)dispatch — the CAS
        // fencing token from ITS OWN attempt was never established as the
        // row's owner, so any of its own mark* calls are stale-owner no-ops.
        assertFalse("$triggerSource must never be able to fence a delivery it did not win", store.markDeliveryAttempt(key, now))
    }

    // --- A. FIRST_UNLOCK wins -------------------------------------------

    @Test
    fun `A - FIRST_UNLOCK wins, all three later sources suppress`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        var t = 1_700_000_000_000L

        val winner = deliverVia(store, "FIRST_UNLOCK", t)
        assertEquals(OccurrenceClaimOutcome.Claimed, winner)
        assertEquals(ProactiveOccurrenceState.DELIVERED.name, dao.rowOrNull(key)!!.state)

        t += 60_000
        assertSuppressed(store, dao, "NEXT_ALARM", t)
        t += 60_000
        assertSuppressed(store, dao, "CONFIGURED_TIME", t)
        t += 60_000
        assertSuppressed(store, dao, "PERIODIC_FALLBACK", t)

        // Exactly one delivery ever happened for this logical date.
        assertEquals("FIRST_UNLOCK", dao.rowOrNull(key)!!.triggerSource)
    }

    // --- B. NEXT_ALARM wins -----------------------------------------------

    @Test
    fun `B - NEXT_ALARM wins, CONFIGURED_TIME and PERIODIC_FALLBACK suppress, and a late FIRST_UNLOCK also suppresses`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        var t = 1_700_000_000_000L

        val winner = deliverVia(store, "NEXT_ALARM", t)
        assertEquals(OccurrenceClaimOutcome.Claimed, winner)

        t += 60_000
        assertSuppressed(store, dao, "CONFIGURED_TIME", t)
        t += 60_000
        assertSuppressed(store, dao, "PERIODIC_FALLBACK", t)
        // A real unlock happening AFTER NEXT_ALARM already delivered (the
        // user unlocks their phone at 08:40, after an 08:35 alarm-derived
        // delivery) must not re-open or duplicate the same logical day.
        t += 60_000
        assertSuppressed(store, dao, "FIRST_UNLOCK", t)

        assertEquals("NEXT_ALARM", dao.rowOrNull(key)!!.triggerSource)
    }

    // --- C. CONFIGURED_TIME wins (fallback path) --------------------------

    @Test
    fun `C - CONFIGURED_TIME wins, later PERIODIC_FALLBACK suppresses`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        var t = 1_700_000_000_000L

        val winner = deliverVia(store, "CONFIGURED_TIME", t)
        assertEquals(OccurrenceClaimOutcome.Claimed, winner)

        t += 3_600_000 // a much later periodic tick, still the same logical day
        assertSuppressed(store, dao, "PERIODIC_FALLBACK", t)

        assertEquals("CONFIGURED_TIME", dao.rowOrNull(key)!!.triggerSource)
    }

    // --- D. Periodic recovery wins after prior signals were unavailable --

    @Test
    fun `D - PERIODIC_FALLBACK recovers when no prior signal claimed the day, and no later signal can re-dispatch`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        var t = 1_700_000_000_000L

        // No FIRST_UNLOCK/NEXT_ALARM/CONFIGURED_TIME ever claimed this day
        // (e.g. automations-in-background was off, no alarm was set, and
        // the configured-time exact alarm was itself missed) — the
        // periodic hourly tick is the only source that ever attempts a
        // claim, and it must still succeed exactly once.
        val winner = deliverVia(store, "PERIODIC_FALLBACK", t)
        assertEquals(OccurrenceClaimOutcome.Claimed, winner)

        // A signal that arrives only AFTER recovery already delivered must
        // never produce a second dispatch.
        t += 60_000
        assertSuppressed(store, dao, "CONFIGURED_TIME", t)
        t += 60_000
        assertSuppressed(store, dao, "NEXT_ALARM", t)
        t += 60_000
        assertSuppressed(store, dao, "FIRST_UNLOCK", t)

        assertEquals("PERIODIC_FALLBACK", dao.rowOrNull(key)!!.triggerSource)
    }

    // --- E. Concurrent/near-simultaneous race — one durable owner only ---

    @Test
    fun `E - two near-simultaneous claims race, exactly one wins and the loser gets an immediate no-op`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val t = 1_700_000_000_000L

        // NEXT_ALARM and CONFIGURED_TIME fire within the same instant (the
        // store's own in-process mutex + the atomic Room insert are what
        // must resolve this, not test-side sequencing) — the FakeDao is
        // single-threaded so this exercises the exact same decision path
        // (ProactiveOccurrenceReconciler.decide + tryInsertClaim) a real
        // concurrent race resolves through, without needing true threads.
        val first = store.claim(key, "MORNING_DIGEST", date, "NEXT_ALARM", t)
        val second = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", t)

        assertEquals(OccurrenceClaimOutcome.Claimed, first)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.CLAIMED), second)
        assertEquals("the row's owner must be exactly the winner, never the loser", "NEXT_ALARM", dao.rowOrNull(key)!!.triggerSource)

        // The winner alone can carry the claim through to delivery.
        assertTrue(store.markDeliveryAttempt(key, t))
        assertTrue(store.markDelivered(key, t))
        // The loser, holding no valid fencing token, can never dispatch.
        assertFalse(store.markDeliveryAttempt(key, t + 1))

        // A third source arriving after the race is resolved is suppressed too.
        assertSuppressed(store, dao, "PERIODIC_FALLBACK", t + 3_600_000)
    }
}
