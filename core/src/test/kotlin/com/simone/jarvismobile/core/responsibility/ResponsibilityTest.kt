package com.simone.jarvismobile.core.responsibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1A. Pure-`:core` coverage for the
 * Responsibility model: key round-trip, the lifecycle transition validator
 * (every legal edge, every terminal/same-state/illegal rejection), the
 * verification-outcome mapping (especially UNKNOWN never authorizing a
 * blind retry), and the journal's bounding policy.
 */
class ResponsibilityTest {

    private val now = 1_700_000_000_000L

    private fun record(
        state: ResponsibilityLifecycleState,
        revision: Long = 1L,
    ) = ResponsibilityRecord(
        key = ResponsibilityKey(ResponsibilityType.MORNING_ASSISTANCE, "2026-10-01"),
        lifecycleState = state,
        revision = revision,
        createdAtMs = now,
        updatedAtMs = now,
    )

    // --- ResponsibilityKey -------------------------------------------------

    @Test
    fun `key toString and parse round trip`() {
        val key = ResponsibilityKey(ResponsibilityType.MORNING_ASSISTANCE, "2026-10-01")
        val raw = key.toString()
        assertEquals("MORNING_ASSISTANCE:2026-10-01", raw)
        assertEquals(key, ResponsibilityKey.parse(raw))
    }

    @Test
    fun `key parse rejects malformed or unknown-type strings without throwing`() {
        assertNull(ResponsibilityKey.parse("no-colon-here"))
        assertNull(ResponsibilityKey.parse("MORNING_ASSISTANCE:"))
        assertNull(ResponsibilityKey.parse(":2026-10-01"))
        assertNull(ResponsibilityKey.parse("NOT_A_REAL_TYPE:2026-10-01"))
    }

    // --- lifecycle transition validator -------------------------------------

    @Test
    fun `every documented legal edge is accepted`() {
        val legal = listOf(
            ResponsibilityLifecycleState.WAITING to ResponsibilityLifecycleState.READY,
            ResponsibilityLifecycleState.WAITING to ResponsibilityLifecycleState.BLOCKED,
            ResponsibilityLifecycleState.WAITING to ResponsibilityLifecycleState.EXPIRED,
            ResponsibilityLifecycleState.READY to ResponsibilityLifecycleState.ACTING,
            ResponsibilityLifecycleState.READY to ResponsibilityLifecycleState.WAITING,
            ResponsibilityLifecycleState.READY to ResponsibilityLifecycleState.BLOCKED,
            ResponsibilityLifecycleState.READY to ResponsibilityLifecycleState.EXPIRED,
            ResponsibilityLifecycleState.ACTING to ResponsibilityLifecycleState.VERIFYING,
            ResponsibilityLifecycleState.ACTING to ResponsibilityLifecycleState.BLOCKED,
            ResponsibilityLifecycleState.ACTING to ResponsibilityLifecycleState.FAILED,
            ResponsibilityLifecycleState.VERIFYING to ResponsibilityLifecycleState.COMPLETED,
            ResponsibilityLifecycleState.VERIFYING to ResponsibilityLifecycleState.ACTING,
            ResponsibilityLifecycleState.VERIFYING to ResponsibilityLifecycleState.WAITING,
            ResponsibilityLifecycleState.VERIFYING to ResponsibilityLifecycleState.BLOCKED,
            ResponsibilityLifecycleState.VERIFYING to ResponsibilityLifecycleState.FAILED,
            ResponsibilityLifecycleState.BLOCKED to ResponsibilityLifecycleState.WAITING,
            ResponsibilityLifecycleState.BLOCKED to ResponsibilityLifecycleState.READY,
            ResponsibilityLifecycleState.BLOCKED to ResponsibilityLifecycleState.EXPIRED,
            ResponsibilityLifecycleState.BLOCKED to ResponsibilityLifecycleState.FAILED,
        )
        legal.forEach { (from, to) ->
            assertTrue(ResponsibilityLifecycle.isValidTransition(from, to), "expected $from -> $to to be legal")
        }
    }

    @Test
    fun `edges not in the documented table are rejected`() {
        val illegal = listOf(
            ResponsibilityLifecycleState.WAITING to ResponsibilityLifecycleState.ACTING,
            ResponsibilityLifecycleState.WAITING to ResponsibilityLifecycleState.VERIFYING,
            ResponsibilityLifecycleState.WAITING to ResponsibilityLifecycleState.COMPLETED,
            ResponsibilityLifecycleState.READY to ResponsibilityLifecycleState.VERIFYING,
            ResponsibilityLifecycleState.READY to ResponsibilityLifecycleState.COMPLETED,
            ResponsibilityLifecycleState.ACTING to ResponsibilityLifecycleState.READY,
            ResponsibilityLifecycleState.ACTING to ResponsibilityLifecycleState.COMPLETED,
            ResponsibilityLifecycleState.ACTING to ResponsibilityLifecycleState.WAITING,
            ResponsibilityLifecycleState.BLOCKED to ResponsibilityLifecycleState.ACTING,
            ResponsibilityLifecycleState.BLOCKED to ResponsibilityLifecycleState.VERIFYING,
            ResponsibilityLifecycleState.BLOCKED to ResponsibilityLifecycleState.COMPLETED,
        )
        illegal.forEach { (from, to) ->
            assertFalse(ResponsibilityLifecycle.isValidTransition(from, to), "expected $from -> $to to be illegal")
        }
    }

    @Test
    fun `terminal states reject every outgoing transition unconditionally`() {
        val terminals = listOf(
            ResponsibilityLifecycleState.COMPLETED,
            ResponsibilityLifecycleState.FAILED,
            ResponsibilityLifecycleState.EXPIRED,
        )
        val anyNonTerminal = ResponsibilityLifecycleState.entries.filterNot { it.isTerminal }
        terminals.forEach { terminal ->
            assertTrue(terminal.isTerminal)
            anyNonTerminal.forEach { target ->
                assertFalse(
                    ResponsibilityLifecycle.isValidTransition(terminal, target),
                    "expected terminal $terminal -> $target to be rejected",
                )
            }
            val result = ResponsibilityLifecycle.transition(record(terminal), anyNonTerminal.first(), now + 1, revisionAfter = 2L)
            assertEquals(
                ResponsibilityTransitionResult.Rejected(ResponsibilityTransitionRejection.TERMINAL_STATE_IMMUTABLE),
                result,
            )
        }
    }

    @Test
    fun `same-state transition is always rejected as a no-op, never silently applied`() {
        ResponsibilityLifecycleState.entries.filterNot { it.isTerminal }.forEach { state ->
            assertFalse(ResponsibilityLifecycle.isValidTransition(state, state))
            val result = ResponsibilityLifecycle.transition(record(state), state, now + 1, revisionAfter = 2L)
            assertEquals(ResponsibilityTransitionResult.Rejected(ResponsibilityTransitionRejection.SAME_STATE_NOOP), result)
        }
    }

    @Test
    fun `an illegal edge is rejected with ILLEGAL_EDGE, never partially applied`() {
        val result = ResponsibilityLifecycle.transition(
            record(ResponsibilityLifecycleState.WAITING),
            ResponsibilityLifecycleState.COMPLETED,
            now + 1,
            revisionAfter = 2L,
        )
        assertEquals(ResponsibilityTransitionResult.Rejected(ResponsibilityTransitionRejection.ILLEGAL_EDGE), result)
    }

    @Test
    fun `a legal transition bumps revision and updatedAt while preserving unrelated fields`() {
        val original = record(ResponsibilityLifecycleState.WAITING, revision = 5L).copy(
            priority = 3,
            linkedActionOccurrenceKey = "MORNING_DIGEST:2026-10-01",
        )
        val result = ResponsibilityLifecycle.transition(
            original,
            ResponsibilityLifecycleState.READY,
            now + 1_000,
            revisionAfter = 6L,
            decision = ResponsibilityDecision.WAIT,
        )
        val applied = (result as? ResponsibilityTransitionResult.Applied)?.record ?: fail("expected Applied, got $result")
        assertEquals(ResponsibilityLifecycleState.READY, applied.lifecycleState)
        assertEquals(6L, applied.revision)
        assertEquals(now + 1_000, applied.updatedAtMs)
        assertEquals(now, applied.createdAtMs)
        assertEquals(3, applied.priority)
        assertEquals("MORNING_DIGEST:2026-10-01", applied.linkedActionOccurrenceKey)
        assertEquals(ResponsibilityDecision.WAIT, applied.lastDecision)
        // the original record passed in must never be mutated in place
        assertEquals(ResponsibilityLifecycleState.WAITING, original.lifecycleState)
        assertEquals(5L, original.revision)
    }

    @Test
    fun `a transition that omits decision or verification outcome preserves the previous value instead of clearing it`() {
        val original = record(ResponsibilityLifecycleState.ACTING).copy(
            lastDecision = ResponsibilityDecision.ACT,
            lastVerificationOutcome = ResponsibilityVerificationOutcome.RETRYABLE_FAILURE,
        )
        val result = ResponsibilityLifecycle.transition(original, ResponsibilityLifecycleState.VERIFYING, now, revisionAfter = 2L)
        val applied = (result as? ResponsibilityTransitionResult.Applied)?.record ?: fail("expected Applied, got $result")
        assertEquals(ResponsibilityDecision.ACT, applied.lastDecision)
        assertEquals(ResponsibilityVerificationOutcome.RETRYABLE_FAILURE, applied.lastVerificationOutcome)
    }

    // --- verification outcome mapping — the UNKNOWN-never-retries invariant -

    @Test
    fun `SUCCESS maps VERIFYING to COMPLETED`() {
        assertEquals(
            ResponsibilityLifecycleState.COMPLETED,
            ResponsibilityLifecycle.targetStateForVerification(ResponsibilityLifecycleState.VERIFYING, ResponsibilityVerificationOutcome.SUCCESS),
        )
    }

    @Test
    fun `RETRYABLE_FAILURE maps VERIFYING to ACTING`() {
        assertEquals(
            ResponsibilityLifecycleState.ACTING,
            ResponsibilityLifecycle.targetStateForVerification(ResponsibilityLifecycleState.VERIFYING, ResponsibilityVerificationOutcome.RETRYABLE_FAILURE),
        )
    }

    @Test
    fun `TERMINAL_FAILURE maps VERIFYING to FAILED`() {
        assertEquals(
            ResponsibilityLifecycleState.FAILED,
            ResponsibilityLifecycle.targetStateForVerification(ResponsibilityLifecycleState.VERIFYING, ResponsibilityVerificationOutcome.TERMINAL_FAILURE),
        )
    }

    @Test
    fun `UNKNOWN maps VERIFYING to BLOCKED, never to ACTING — no blind retry`() {
        val target = ResponsibilityLifecycle.targetStateForVerification(ResponsibilityLifecycleState.VERIFYING, ResponsibilityVerificationOutcome.UNKNOWN)
        assertEquals(ResponsibilityLifecycleState.BLOCKED, target)
        assertFalse(target == ResponsibilityLifecycleState.ACTING)
        // and the mapped target must itself be a legal edge from VERIFYING.
        assertTrue(ResponsibilityLifecycle.isValidTransition(ResponsibilityLifecycleState.VERIFYING, target!!))
    }

    @Test
    fun `verification mapping is null for any state other than VERIFYING — never guessed at`() {
        ResponsibilityLifecycleState.entries.filterNot { it == ResponsibilityLifecycleState.VERIFYING }.forEach { state ->
            assertNull(ResponsibilityLifecycle.targetStateForVerification(state, ResponsibilityVerificationOutcome.SUCCESS))
        }
    }

    // --- ResponsibilityRecord bounds ----------------------------------------

    @Test
    fun `priority outside the documented range is rejected at construction`() {
        runCatching { record(ResponsibilityLifecycleState.WAITING).copy(priority = -1) }
            .onSuccess { fail("expected an exception for priority below the minimum") }
        runCatching { record(ResponsibilityLifecycleState.WAITING).copy(priority = ResponsibilityRecord.MAX_PRIORITY + 1) }
            .onSuccess { fail("expected an exception for priority above the maximum") }
    }

    @Test
    fun `an overlong terminalReason is rejected at construction, never silently truncated`() {
        val tooLong = "x".repeat(ResponsibilityRecord.MAX_REASON_CHARS + 1)
        runCatching { record(ResponsibilityLifecycleState.FAILED).copy(terminalReason = tooLong) }
            .onSuccess { fail("expected an exception for an overlong terminalReason") }
    }

    // --- journal bounding policy ---------------------------------------------

    @Test
    fun `sanitizeReasonCode returns null for null, blank, or whitespace-only input`() {
        assertNull(ResponsibilityJournalPolicy.sanitizeReasonCode(null))
        assertNull(ResponsibilityJournalPolicy.sanitizeReasonCode(""))
        assertNull(ResponsibilityJournalPolicy.sanitizeReasonCode("   \n  "))
    }

    @Test
    fun `sanitizeReasonCode strips newlines and bounds length`() {
        val withNewlines = "verification_outcome=UNKNOWN\nsource=occurrence"
        val sanitized = ResponsibilityJournalPolicy.sanitizeReasonCode(withNewlines)
        assertEquals("verification_outcome=UNKNOWN source=occurrence", sanitized)

        val tooLong = "a".repeat(ResponsibilityJournalPolicy.MAX_REASON_CODE_CHARS + 50)
        val boundedResult = ResponsibilityJournalPolicy.sanitizeReasonCode(tooLong)
        assertEquals(ResponsibilityJournalPolicy.MAX_REASON_CODE_CHARS, boundedResult?.length)
    }
}
