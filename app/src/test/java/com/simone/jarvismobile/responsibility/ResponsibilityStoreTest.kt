package com.simone.jarvismobile.responsibility

import com.simone.jarvismobile.core.responsibility.ResponsibilityDecision
import com.simone.jarvismobile.core.responsibility.ResponsibilityKey
import com.simone.jarvismobile.core.responsibility.ResponsibilityLifecycleState
import com.simone.jarvismobile.core.responsibility.ResponsibilityTransitionRejection
import com.simone.jarvismobile.core.responsibility.ResponsibilityType
import com.simone.jarvismobile.core.responsibility.ResponsibilityVerificationOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1A. Store-level integration tests
 * for [ResponsibilityStore]'s durable creation + CAS-fenced transitions +
 * bounded journal, using [FakeResponsibilityDao]/[FakeResponsibilityJournalDao]
 * — plain JVM, no Robolectric, same pattern as
 * `com.simone.jarvismobile.proactive.ProactiveOccurrenceStoreTest`.
 */
class ResponsibilityStoreTest {

    private val key = ResponsibilityKey(ResponsibilityType.MORNING_ASSISTANCE, "2026-10-01")
    private val now = 2_000_000_000L

    private fun newStore(dao: ResponsibilityDao = FakeResponsibilityDao()): Pair<ResponsibilityStore, FakeResponsibilityJournalDao> {
        val journalDao = FakeResponsibilityJournalDao()
        return ResponsibilityStore(dao, journalDao) to journalDao
    }

    // --- createIfAbsent ------------------------------------------------------

    @Test
    fun `createIfAbsent on an empty table creates at revision 1 and journals it`() = runTest {
        val (store, journalDao) = newStore()
        val outcome = store.createIfAbsent(key, now = now)
        val created = (outcome as? ResponsibilityStore.CreateOutcome.Created)?.record
            ?: error("expected Created, got $outcome")
        assertEquals(ResponsibilityLifecycleState.WAITING, created.lifecycleState)
        assertEquals(1L, created.revision)
        assertEquals(now, created.createdAtMs)

        val journalRows = journalDao.all()
        assertEquals(1, journalRows.size)
        assertEquals(null, journalRows.single().fromState)
        assertEquals("WAITING", journalRows.single().toState)
    }

    @Test
    fun `createIfAbsent on an existing key is a no-op that reports the real owner, never a second row`() = runTest {
        val dao = FakeResponsibilityDao()
        val (store, journalDao) = newStore(dao)
        store.createIfAbsent(key, now = now)
        val rowAfterFirst = dao.rowOrNull(key.toString())!!.copy()

        val second = store.createIfAbsent(key, now = now + 1_000)

        assertTrue(second is ResponsibilityStore.CreateOutcome.AlreadyExists)
        // the loser performs no write at all: the row is byte-for-byte unchanged.
        assertEquals(rowAfterFirst, dao.rowOrNull(key.toString()))
        assertEquals(1, journalDao.all().size)
    }

    @Test
    fun `createIfAbsent reports a seeded existing row honestly instead of assuming its own shape`() = runTest {
        val dao = FakeResponsibilityDao()
        dao.seed(
            ResponsibilityEntity(
                logicalKey = key.toString(), type = key.type.name, lifecycleState = "READY",
                revision = 7L, createdAtMs = 1L, updatedAtMs = 1L,
            ),
        )
        val (store, _) = newStore(dao)
        val outcome = store.createIfAbsent(key, now = now)
        val existing = (outcome as? ResponsibilityStore.CreateOutcome.AlreadyExists)?.record
            ?: error("expected AlreadyExists, got $outcome")
        assertEquals(ResponsibilityLifecycleState.READY, existing.lifecycleState)
        assertEquals(7L, existing.revision)
    }

    // --- get -------------------------------------------------------------------

    @Test
    fun `get returns null for a key that was never created`() = runTest {
        val (store, _) = newStore()
        assertNull(store.get(key))
    }

    @Test
    fun `get round-trips every optional field`() = runTest {
        val (store, _) = newStore()
        store.createIfAbsent(key, priority = 5, deadlineAtMs = 999L, now = now)
        val record = store.get(key) ?: error("expected a record")
        assertEquals(5, record.priority)
        assertEquals(999L, record.deadlineAtMs)
    }

    // --- transition --------------------------------------------------------------

    @Test
    fun `transition on a key that was never created returns NotFound`() = runTest {
        val (store, _) = newStore()
        val outcome = store.transition(key, ResponsibilityLifecycleState.READY, now = now)
        assertEquals(ResponsibilityStore.TransitionOutcome.NotFound, outcome)
    }

    @Test
    fun `a legal transition bumps the durable revision and appends a journal entry`() = runTest {
        val (store, journalDao) = newStore()
        store.createIfAbsent(key, now = now)
        val outcome = store.transition(
            key, ResponsibilityLifecycleState.READY,
            decision = ResponsibilityDecision.WAIT, reasonCode = "observation_sufficient", now = now + 1_000,
        )
        val applied = (outcome as? ResponsibilityStore.TransitionOutcome.Applied)?.record
            ?: error("expected Applied, got $outcome")
        assertEquals(ResponsibilityLifecycleState.READY, applied.lifecycleState)
        assertEquals(2L, applied.revision)

        val journalRows = journalDao.all()
        assertEquals(2, journalRows.size)
        val transitionEntry = journalRows.last()
        assertEquals("WAITING", transitionEntry.fromState)
        assertEquals("READY", transitionEntry.toState)
        assertEquals("observation_sufficient", transitionEntry.reasonCode)
    }

    @Test
    fun `an illegal edge is rejected and performs no durable write or journal entry`() = runTest {
        val dao = FakeResponsibilityDao()
        val (store, journalDao) = newStore(dao)
        store.createIfAbsent(key, now = now)
        val rowBefore = dao.rowOrNull(key.toString())!!.copy()

        val outcome = store.transition(key, ResponsibilityLifecycleState.COMPLETED, now = now + 1_000)

        assertEquals(
            ResponsibilityStore.TransitionOutcome.Rejected(ResponsibilityTransitionRejection.ILLEGAL_EDGE),
            outcome,
        )
        assertEquals(rowBefore, dao.rowOrNull(key.toString()))
        assertEquals(1, journalDao.all().size) // only the creation entry
    }

    @Test
    fun `a terminal responsibility rejects every further transition attempt`() = runTest {
        val (store, _) = newStore()
        store.createIfAbsent(key, now = now)
        store.transition(key, ResponsibilityLifecycleState.BLOCKED, now = now)
        store.transition(key, ResponsibilityLifecycleState.FAILED, now = now)

        val outcome = store.transition(key, ResponsibilityLifecycleState.WAITING, now = now + 1)
        assertEquals(
            ResponsibilityStore.TransitionOutcome.Rejected(ResponsibilityTransitionRejection.TERMINAL_STATE_IMMUTABLE),
            outcome,
        )
    }

    @Test
    fun `a concurrent writer that moved the row between read and write surfaces as StaleRevision, never a silent overwrite`() = runTest {
        val dao = FakeResponsibilityDao()
        val racingDao = RacingResponsibilityDao(dao)
        val (store, _) = newStore(racingDao)
        store.createIfAbsent(key, now = now)

        racingDao.raceOnNextFind = true
        val outcome = store.transition(key, ResponsibilityLifecycleState.READY, now = now + 1_000)

        assertEquals(ResponsibilityStore.TransitionOutcome.StaleRevision, outcome)
        // the row's revision reflects the "concurrent writer", never this caller's stale write.
        assertEquals(2L, dao.rowOrNull(key.toString())?.revision)
        assertEquals("WAITING", dao.rowOrNull(key.toString())?.lifecycleState)
    }

    @Test
    fun `UNKNOWN verification outcome moves a VERIFYING responsibility to BLOCKED, never back to ACTING`() = runTest {
        val (store, _) = newStore()
        store.createIfAbsent(key, now = now)
        store.transition(key, ResponsibilityLifecycleState.READY, now = now)
        store.transition(key, ResponsibilityLifecycleState.ACTING, decision = ResponsibilityDecision.ACT, now = now)
        store.transition(key, ResponsibilityLifecycleState.VERIFYING, decision = ResponsibilityDecision.VERIFY, now = now)

        val outcome = store.transition(
            key, ResponsibilityLifecycleState.BLOCKED,
            verificationOutcome = ResponsibilityVerificationOutcome.UNKNOWN, now = now + 1,
        )
        val applied = (outcome as? ResponsibilityStore.TransitionOutcome.Applied)?.record
            ?: error("expected Applied, got $outcome")
        assertEquals(ResponsibilityLifecycleState.BLOCKED, applied.lifecycleState)
        assertEquals(ResponsibilityVerificationOutcome.UNKNOWN, applied.lastVerificationOutcome)
    }

    // --- journal -------------------------------------------------------------------

    @Test
    fun `journal returns entries newest-first and respects the limit`() = runTest {
        val (store, _) = newStore()
        store.createIfAbsent(key, now = now)
        store.transition(key, ResponsibilityLifecycleState.READY, now = now + 1)
        store.transition(key, ResponsibilityLifecycleState.WAITING, now = now + 2)
        store.transition(key, ResponsibilityLifecycleState.READY, now = now + 3)

        val entries = store.journal(key, limit = 2)
        assertEquals(2, entries.size)
        assertEquals(now + 3, entries.first().atMs)
        assertEquals(now + 2, entries.last().atMs)
    }

    @Test
    fun `journal reasonCode is sanitized - newlines stripped`() = runTest {
        val (store, _) = newStore()
        store.createIfAbsent(key, now = now)
        store.transition(key, ResponsibilityLifecycleState.READY, reasonCode = "line1\nline2", now = now + 1)

        val entry = store.journal(key).first()
        assertEquals("line1 line2", entry.reasonCode)
    }

    @Test
    fun `journal is bounded per key - older entries are pruned beyond the cap`() = runTest {
        val (store, journalDao) = newStore()
        store.createIfAbsent(key, now = now)
        // Bounce between two legal, opposite edges (WAITING <-> READY) enough
        // times to exceed ResponsibilityJournalPolicy.MAX_ENTRIES_PER_KEY (40),
        // including the initial "created" entry.
        var current = ResponsibilityLifecycleState.WAITING
        repeat(45) { i ->
            val next = if (current == ResponsibilityLifecycleState.WAITING) ResponsibilityLifecycleState.READY else ResponsibilityLifecycleState.WAITING
            store.transition(key, next, now = now + i + 1)
            current = next
        }
        assertTrue("expected the journal to be capped, was ${journalDao.all().size}", journalDao.all().size <= 40)
    }

    // --- prune -----------------------------------------------------------------------

    @Test
    fun `prune removes only terminal rows older than the cutoff, never an in-flight one`() = runTest {
        val dao = FakeResponsibilityDao()
        val (store, _) = newStore(dao)
        val oldKey = ResponsibilityKey(ResponsibilityType.MORNING_ASSISTANCE, "2026-01-01")
        val inFlightKey = ResponsibilityKey(ResponsibilityType.MORNING_ASSISTANCE, "2026-01-02")

        store.createIfAbsent(oldKey, now = 0L)
        store.transition(oldKey, ResponsibilityLifecycleState.BLOCKED, now = 0L)
        store.transition(oldKey, ResponsibilityLifecycleState.FAILED, now = 0L)

        store.createIfAbsent(inFlightKey, now = 0L)

        val farFuture = 365L * 24 * 60 * 60 * 1000L
        store.prune(retentionDays = 30L, now = farFuture)

        assertNull(dao.rowOrNull(oldKey.toString()))
        assertEquals(ResponsibilityLifecycleState.WAITING.name, dao.rowOrNull(inFlightKey.toString())?.lifecycleState)
    }
}
