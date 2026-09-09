package com.simone.jarvismobile.proactive

import com.simone.jarvismobile.core.proactive.OccurrenceClaimOutcome
import com.simone.jarvismobile.core.proactive.ProactiveOccurrenceState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.1 §V. Store-level
 * integration tests for the durable morning-digest occurrence claim, using
 * [FakeProactiveOccurrenceDao] — plain JVM, no Robolectric, same pattern as
 * `DurableDedupTest.kt`.
 */
class ProactiveOccurrenceStoreTest {

    private val date = LocalDate.of(2026, 9, 9)
    private val key = "MORNING_DIGEST:$date"
    private val now = 2_000_000_000L

    @Test
    fun `a fresh claim on an empty table succeeds`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        assertEquals(OccurrenceClaimOutcome.Claimed, outcome)
        val row = dao.rowOrNull(key)
        assertNotNull(row)
        assertEquals(ProactiveOccurrenceState.CLAIMED.name, row!!.state)
        assertEquals("FIRST_UNLOCK", row.triggerSource)
    }

    @Test
    fun `a second claim after the first wins is a no-op loser - no side effect`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val first = store.claim(key, "MORNING_DIGEST", date, "NEXT_ALARM", now)
        val rowAfterFirst = dao.rowOrNull(key)!!.copy()

        val second = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now + 1_000)

        assertEquals(OccurrenceClaimOutcome.Claimed, first)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.CLAIMED), second)
        // the loser must perform NO side effect: the row is unchanged
        assertEquals(rowAfterFirst, dao.rowOrNull(key))
    }

    @Test
    fun `DELIVERED suppresses every later trigger regardless of source`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = date.toString(),
                state = ProactiveOccurrenceState.DELIVERED.name, triggerSource = "FIRST_UNLOCK",
                claimedAtMs = now - 999_999_999, deliveredAtMs = now - 999_999_000,
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), outcome)
    }

    @Test
    fun `FAILED_RETRYABLE allows an immediate real takeover`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = date.toString(),
                state = ProactiveOccurrenceState.FAILED_RETRYABLE.name, triggerSource = "FIRST_UNLOCK",
                claimedAtMs = now - 1_000, retryCount = 1, terminalReason = "skip:quiet_hours",
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        assertEquals(OccurrenceClaimOutcome.TakeoverAllowed, outcome)
        val row = dao.rowOrNull(key)!!
        assertEquals(ProactiveOccurrenceState.CLAIMED.name, row.state)
        assertEquals("CONFIGURED_TIME", row.triggerSource)
        assertEquals(2, row.retryCount)
        assertNull(row.terminalReason)
    }

    @Test
    fun `a fresh in-flight row - UNKNOWN outcome window - is never taken over`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = date.toString(),
                state = ProactiveOccurrenceState.DELIVERY_PENDING.name, triggerSource = "FIRST_UNLOCK",
                claimedAtMs = now - 1_000,
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERY_PENDING), outcome)
    }

    @Test
    fun `a stale in-flight row past the staleness threshold allows takeover - crash recovery`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val staleAfterMs = 10 * 60 * 1000L
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = date.toString(),
                state = ProactiveOccurrenceState.CLAIMED.name, triggerSource = "FIRST_UNLOCK",
                claimedAtMs = now - staleAfterMs - 60_000,
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "PERIODIC_FALLBACK", now)
        assertEquals(OccurrenceClaimOutcome.TakeoverAllowed, outcome)
        assertEquals(ProactiveOccurrenceState.CLAIMED.name, dao.rowOrNull(key)!!.state)
    }

    @Test
    fun `different logical dates never collide`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val today = store.claim("MORNING_DIGEST:2026-09-09", "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        val tomorrow = store.claim("MORNING_DIGEST:2026-09-10", "MORNING_DIGEST", date.plusDays(1), "FIRST_UNLOCK", now)
        assertEquals(OccurrenceClaimOutcome.Claimed, today)
        assertEquals(OccurrenceClaimOutcome.Claimed, tomorrow)
    }

    @Test
    fun `markGenerated then markDeliveryAttempt then markDelivered progress the state machine`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)

        store.markGenerated(key)
        assertEquals(ProactiveOccurrenceState.GENERATED.name, dao.rowOrNull(key)!!.state)
        assertNotNull(dao.rowOrNull(key)!!.generatedAtMs)

        store.markDeliveryAttempt(key)
        assertEquals(ProactiveOccurrenceState.DELIVERY_PENDING.name, dao.rowOrNull(key)!!.state)
        assertNotNull(dao.rowOrNull(key)!!.deliveryAttemptAtMs)

        store.markDelivered(key)
        assertEquals(ProactiveOccurrenceState.DELIVERED.name, dao.rowOrNull(key)!!.state)
        assertNotNull(dao.rowOrNull(key)!!.deliveredAtMs)
    }

    @Test
    fun `markFailedRetryable releases the claim so a later trigger can retry`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        store.markFailedRetryable(key, "skip:budget_exhausted")
        assertEquals(ProactiveOccurrenceState.FAILED_RETRYABLE.name, dao.rowOrNull(key)!!.state)

        val retry = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now + 60_000)
        assertEquals(OccurrenceClaimOutcome.TakeoverAllowed, retry)
    }

    @Test
    fun `pruneOld deletes rows older than the retention window`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = "MORNING_DIGEST:2020-01-01", kind = "MORNING_DIGEST", logicalDate = "2020-01-01",
                state = ProactiveOccurrenceState.DELIVERED.name, triggerSource = "FIRST_UNLOCK", claimedAtMs = 0L,
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        store.pruneOld(retentionDays = 7, now = now)
        assertNull(dao.rowOrNull("MORNING_DIGEST:2020-01-01"))
    }

    @Test
    fun `a lost race between read and atomic insert is honestly re-reported as AlreadyOwned`() = runTest {
        // Simulates a genuinely concurrent cross-process claim: the store's own
        // read sees no row (existingState == null -> Claimed decision), but the
        // atomic DB insert loses the real race and returns -1L — the store must
        // re-read and report the truth, never silently proceed as if it owned it.
        val dao = object : ProactiveOccurrenceDao {
            private val delegate = FakeProactiveOccurrenceDao()
            private var findCalls = 0

            override suspend fun find(key: String): ProactiveOccurrenceEntity? {
                findCalls++
                return if (findCalls == 1) {
                    null
                } else {
                    delegate.seed(
                        ProactiveOccurrenceEntity(
                            occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = "2026-09-09",
                            state = ProactiveOccurrenceState.CLAIMED.name, triggerSource = "OTHER_PROCESS",
                            claimedAtMs = now,
                        ),
                    )
                    delegate.find(key)
                }
            }

            override suspend fun tryInsertClaim(entity: ProactiveOccurrenceEntity): Long = -1L

            override suspend fun tryTakeover(key: String, staleCutoffMs: Long, newState: String, triggerSource: String, nowMs: Long): Int = 0

            override suspend fun markGenerated(key: String, state: String, atMs: Long) {}
            override suspend fun markDeliveryAttempt(key: String, state: String, atMs: Long) {}
            override suspend fun markDelivered(key: String, state: String, atMs: Long) {}
            override suspend fun markFailed(key: String, state: String, reason: String?) {}
            override suspend fun deleteOlderThan(cutoffMs: Long): Int = 0
        }
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        assertTrue(outcome is OccurrenceClaimOutcome.AlreadyOwned)
        assertEquals(ProactiveOccurrenceState.CLAIMED, (outcome as OccurrenceClaimOutcome.AlreadyOwned).state)
    }

    @Test
    fun `lastClaim diagnostic reflects the most recent claim decision without ever carrying briefing text`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        val diag = store.lastClaim.value
        assertNotNull(diag)
        assertEquals(key, diag!!.occurrenceKey)
        assertEquals("FIRST_UNLOCK", diag.triggerSource)
        assertEquals("claimed", diag.outcome)
    }
}
