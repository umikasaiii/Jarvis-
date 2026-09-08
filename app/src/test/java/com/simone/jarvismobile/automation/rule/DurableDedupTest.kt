package com.simone.jarvismobile.automation.rule

import com.simone.jarvismobile.core.automation.rule.ActionSpec
import com.simone.jarvismobile.core.automation.rule.AutomationRule
import com.simone.jarvismobile.core.automation.rule.TriggerEvent
import com.simone.jarvismobile.core.automation.rule.TriggerRegistry
import com.simone.jarvismobile.core.automation.rule.TriggerSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

/**
 * Covers the explicitly required PASSAGGIO 8.1 durable-dedup scenarios
 * (§10 test items 1-3, 6, 9, 11-13) against a real [ExecutionLogRepository]
 * backed by a hand-rolled fake [AutomationExecutionDao] — a plain Kotlin
 * interface with no Android-typed members, so this runs on the plain JVM
 * with no Room/Robolectric needed, the same pattern already established by
 * `EventBridgeTest`'s fakes.
 */
class DurableDedupTest {

    private val now = LocalDateTime.of(2026, 9, 8, 8, 0)

    private fun rule(id: String = "r1") = AutomationRule(
        id = id,
        name = "Test",
        triggers = listOf(TriggerSpec(TriggerRegistry.RECURRING_TIME)),
        actions = listOf(ActionSpec("SPEAK", mapOf("message" to "ok"))),
    )

    // --- item 1: a committed occurrence blocks a duplicate --------------

    @Test
    fun `a committed row blocks a duplicate delivery`() = runTest {
        val dao = FakeAutomationExecutionDao()
        dao.rows += committedRow(key = "r1|RECURRING_TIME|2026-09-08T08:00", at = now)
        val log = ExecutionLogRepository(dao)
        val event = TriggerEvent(TriggerRegistry.RECURRING_TIME, now, dedupKey = "2026-09-08T08:00")

        val lookup = durableLookup(log, "r1|RECURRING_TIME|2026-09-08T08:00", event)

        assertEquals(DurableLookupState.Committed, lookup)
        assertEquals("occorrenza già eseguita in precedenza", blockingReasonFor(lookup, rule()))
    }

    @Test
    fun `no matching row is not committed and does not block`() = runTest {
        val dao = FakeAutomationExecutionDao()
        val log = ExecutionLogRepository(dao)
        val event = TriggerEvent(TriggerRegistry.RECURRING_TIME, now, dedupKey = "2026-09-08T08:00")

        val lookup = durableLookup(log, "r1|RECURRING_TIME|2026-09-08T08:00", event)

        assertEquals(DurableLookupState.NotCommitted, lookup)
        assertEquals(null, blockingReasonFor(lookup, rule()))
    }

    // --- item 2/3: storage failure is UNKNOWN, and UNKNOWN blocks -------

    @Test
    fun `a storage read failure resolves to Unknown, never NotCommitted`() = runTest {
        val dao = FakeAutomationExecutionDao(throwOnRead = true)
        val log = ExecutionLogRepository(dao)
        val event = TriggerEvent(TriggerRegistry.RECURRING_TIME, now, dedupKey = "2026-09-08T08:00")

        val lookup = durableLookup(log, "r1|RECURRING_TIME|2026-09-08T08:00", event)

        assertEquals(DurableLookupState.Unknown, lookup)
    }

    @Test
    fun `Unknown blocks a mutating rule - never execute merely because dedup storage is unavailable`() {
        assertTrue(blockingReasonFor(DurableLookupState.Unknown, rule()) != null)
    }

    @Test
    fun `Unknown does not block a rule whose every action is registered read-only`() {
        // §6 exemption via the EXISTING ActionRisk classification. No action
        // in today's ActionRegistry is actually READ_ONLY (riskOf() only
        // returns it for an empty action list, an unreachable case for a
        // runnable rule) - this pins that the exemption exists and is wired,
        // not that it fires in practice today.
        val readOnlyRule = rule().copy(actions = emptyList())
        assertEquals(null, blockingReasonFor(DurableLookupState.Unknown, readOnlyRule))
    }

    // --- item 13: cancellation is never swallowed ------------------------

    @Test
    fun `a real CancellationException from storage still propagates as cancellation`() = runTest {
        val dao = FakeAutomationExecutionDao(throwCancellation = true)
        val log = ExecutionLogRepository(dao)
        val event = TriggerEvent(TriggerRegistry.RECURRING_TIME, now, dedupKey = "2026-09-08T08:00")

        try {
            durableLookup(log, "r1|RECURRING_TIME|2026-09-08T08:00", event)
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected — never converted to Unknown/NotCommitted
        }
    }

    // --- item 11/12: scheduled-trigger identity is unbounded, others aren't --

    @Test
    fun `a scheduled trigger's committed row blocks even far outside the 30s window`() = runTest {
        val dao = FakeAutomationExecutionDao()
        val key = "r1|RECURRING_TIME|2026-09-08T08:00"
        dao.rows += committedRow(key, at = now.minusHours(6)) // e.g. after a slow reboot
        val log = ExecutionLogRepository(dao)
        val redelivered = TriggerEvent(TriggerRegistry.RECURRING_TIME, now, dedupKey = "2026-09-08T08:00")

        assertEquals(DurableLookupState.Committed, durableLookup(log, key, redelivered))
    }

    @Test
    fun `a scheduled trigger's DIFFERENT occurrence - tomorrow's slot - is a distinct key, never suppressed`() = runTest {
        val dao = FakeAutomationExecutionDao()
        dao.rows += committedRow("r1|RECURRING_TIME|2026-09-08T08:00", at = now)
        val log = ExecutionLogRepository(dao)
        val tomorrow = now.plusDays(1)
        val newOccurrence = TriggerEvent(TriggerRegistry.RECURRING_TIME, tomorrow, dedupKey = "2026-09-09T08:00")

        // Different dedupKey -> different idempotencyKey entirely; the fake
        // DAO's exact-match query correctly finds nothing for it.
        assertEquals(
            DurableLookupState.NotCommitted,
            durableLookup(log, "r1|RECURRING_TIME|2026-09-09T08:00", newOccurrence),
        )
    }

    @Test
    fun `a non-scheduled trigger - place - with the same key recurring after 30s is NOT suppressed`() = runTest {
        val dao = FakeAutomationExecutionDao()
        val key = "r1|PLACE_ENTER|home"
        dao.rows += committedRow(key, at = now.minusMinutes(5)) // well outside the 30s window
        val log = ExecutionLogRepository(dao)
        val laterArrival = TriggerEvent(TriggerRegistry.PLACE_ENTER, now, dedupKey = "home")

        // PLACE_ENTER is not a scheduled type, so the windowed query applies -
        // a legitimate later arrival at the same place must not be blocked.
        assertEquals(DurableLookupState.NotCommitted, durableLookup(log, key, laterArrival))
    }

    @Test
    fun `a non-scheduled trigger with the same key WITHIN 30s is suppressed`() = runTest {
        val dao = FakeAutomationExecutionDao()
        val key = "r1|PLACE_ENTER|home"
        dao.rows += committedRow(key, at = now.minusSeconds(5))
        val log = ExecutionLogRepository(dao)
        val redelivered = TriggerEvent(TriggerRegistry.PLACE_ENTER, now, dedupKey = "home")

        assertEquals(DurableLookupState.Committed, durableLookup(log, key, redelivered))
    }

    // --- item 6: an INDETERMINATE row blocks exactly like COMMITTED -----

    @Test
    fun `an INDETERMINATE row blocks a duplicate just like COMMITTED`() = runTest {
        val dao = FakeAutomationExecutionDao()
        val key = "r1|RECURRING_TIME|2026-09-08T08:00"
        dao.rows += committedRow(key, at = now, commitState = OccurrenceCommitState.INDETERMINATE)
        val log = ExecutionLogRepository(dao)
        val event = TriggerEvent(TriggerRegistry.RECURRING_TIME, now, dedupKey = "2026-09-08T08:00")

        assertEquals(DurableLookupState.Committed, durableLookup(log, key, event))
    }

    @Test
    fun `a RETRYABLE_NO_EFFECT row does not block a retry`() = runTest {
        val dao = FakeAutomationExecutionDao()
        val key = "r1|RECURRING_TIME|2026-09-08T08:00"
        dao.rows += committedRow(key, at = now, commitState = OccurrenceCommitState.RETRYABLE_NO_EFFECT)
        val log = ExecutionLogRepository(dao)
        val event = TriggerEvent(TriggerRegistry.RECURRING_TIME, now, dedupKey = "2026-09-08T08:00")

        assertEquals(DurableLookupState.NotCommitted, durableLookup(log, key, event))
    }

    @Test
    fun `a dry-run row never counts as committed`() = runTest {
        val dao = FakeAutomationExecutionDao()
        val key = "r1|RECURRING_TIME|2026-09-08T08:00"
        dao.rows += committedRow(key, at = now, dryRun = true)
        val log = ExecutionLogRepository(dao)
        val event = TriggerEvent(TriggerRegistry.RECURRING_TIME, now, dedupKey = "2026-09-08T08:00")

        assertEquals(DurableLookupState.NotCommitted, durableLookup(log, key, event))
    }

    // --- helpers -----------------------------------------------------------

    private fun committedRow(
        key: String,
        at: LocalDateTime,
        commitState: OccurrenceCommitState = OccurrenceCommitState.COMMITTED,
        dryRun: Boolean = false,
    ) = AutomationExecutionEntity(
        executionId = "exec-$key-$at",
        ruleId = "r1",
        ruleName = "Test",
        triggerType = "RECURRING_TIME",
        startedAt = at.toString(),
        finishedAt = at.toString(),
        decision = "FIRE",
        reason = "trigger",
        actionOutcomes = "SPEAK:ok",
        dryRun = dryRun,
        idempotencyKey = key,
        commitState = commitState.name,
    )
}

/** In-memory fake, exactly the interface Room would otherwise generate. */
private class FakeAutomationExecutionDao(
    private val throwOnRead: Boolean = false,
    private val throwCancellation: Boolean = false,
) : AutomationExecutionDao {
    val rows = mutableListOf<AutomationExecutionEntity>()

    override fun observeRecent(limit: Int): Flow<List<AutomationExecutionEntity>> = flowOf(rows.toList())

    override suspend fun forRule(ruleId: String, limit: Int): List<AutomationExecutionEntity> =
        rows.filter { it.ruleId == ruleId }

    override suspend fun insert(execution: AutomationExecutionEntity) {
        rows += execution
    }

    override suspend fun deleteOlderThan(before: String): Int = 0

    override suspend fun count(): Int = rows.size

    override suspend fun countBlockingSince(key: String, sinceIso: String): Int {
        maybeThrow()
        return rows.count {
            it.idempotencyKey == key && it.decision == "FIRE" && !it.dryRun &&
                it.commitState != OccurrenceCommitState.RETRYABLE_NO_EFFECT.name &&
                it.startedAt >= sinceIso
        }
    }

    override suspend fun countBlockingEver(key: String): Int {
        maybeThrow()
        return rows.count {
            it.idempotencyKey == key && it.decision == "FIRE" && !it.dryRun &&
                it.commitState != OccurrenceCommitState.RETRYABLE_NO_EFFECT.name
        }
    }

    private fun maybeThrow() {
        if (throwCancellation) throw CancellationException("cancelled")
        if (throwOnRead) throw IllegalStateException("storage unavailable")
    }
}
