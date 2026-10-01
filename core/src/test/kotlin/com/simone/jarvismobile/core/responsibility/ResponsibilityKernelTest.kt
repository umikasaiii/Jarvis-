package com.simone.jarvismobile.core.responsibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1B. Pure-`:core` coverage for
 * [ResponsibilityKernel.reduce] — the observation model, the generic
 * [ResponsibilityDecisionPolicy] contract (via deterministic fake policies
 * only — no Morning Briefing product policy exists here), and invariants A-I
 * enumerated in [ResponsibilityKernel]'s own doc comment.
 */
class ResponsibilityKernelTest {

    private val now = 1_700_000_000_000L

    private fun record(state: ResponsibilityLifecycleState, deadlineAtMs: Long? = null) = ResponsibilityRecord(
        key = ResponsibilityKey(ResponsibilityType.MORNING_ASSISTANCE, "2026-10-01"),
        lifecycleState = state,
        revision = 1L,
        createdAtMs = now,
        updatedAtMs = now,
        deadlineAtMs = deadlineAtMs,
    )

    private fun wait() = ResponsibilityPolicyResult(ResponsibilityDecision.WAIT, ResponsibilityDecisionReasonCode.OBSERVATION_INSUFFICIENT)
    private fun act() = ResponsibilityPolicyResult(ResponsibilityDecision.ACT, ResponsibilityDecisionReasonCode.READY_TO_ACT)
    private fun verify() = ResponsibilityPolicyResult(ResponsibilityDecision.VERIFY, ResponsibilityDecisionReasonCode.AWAITING_VERIFICATION)
    private fun askUser() = ResponsibilityPolicyResult(ResponsibilityDecision.ASK_USER, ResponsibilityDecisionReasonCode.NEEDS_USER_INPUT)
    private fun abort() = ResponsibilityPolicyResult(ResponsibilityDecision.ABORT, ResponsibilityDecisionReasonCode.ABORTED)
    private fun complete() = ResponsibilityPolicyResult(ResponsibilityDecision.COMPLETE, ResponsibilityDecisionReasonCode.VERIFICATION_SUCCESS)
    private fun recheckAt(at: Long?) = ResponsibilityPolicyResult(ResponsibilityDecision.RECHECK_AT, ResponsibilityDecisionReasonCode.RECHECK_SCHEDULED, recheckAtMs = at)

    private val allObservations: List<ResponsibilityObservation> = listOf(
        ResponsibilityObservation.ExternalEventObserved(ResponsibilityTriggerIdentifier.FIRST_UNLOCK, now),
        ResponsibilityObservation.RecheckDue(now),
        ResponsibilityObservation.ProcessRestored(now),
        ResponsibilityObservation.DeadlineReached(now),
        ResponsibilityObservation.CapabilityChanged(granted = true, atMs = now),
        ResponsibilityObservation.VerificationResultObserved(ResponsibilityVerificationOutcome.SUCCESS, now),
    )

    // --- Invariant A: terminal immutability -------------------------------

    @Test
    fun `1 - terminal lifecycle states reject every observation unconditionally, regardless of policy`() {
        val terminals = listOf(ResponsibilityLifecycleState.COMPLETED, ResponsibilityLifecycleState.FAILED, ResponsibilityLifecycleState.EXPIRED)
        terminals.forEach { terminal ->
            allObservations.forEach { obs ->
                val result = ResponsibilityKernel.reduce(record(terminal), obs, act(), now)
                assertEquals(ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.TERMINAL_STATE_IMMUTABLE), result)
            }
        }
    }

    // --- Invariant B: deadline/EXPIRED correctness, both directions -------

    @Test
    fun `2 - DeadlineReached proposes EXPIRED only when it is a legal edge from the current state`() {
        listOf(ResponsibilityLifecycleState.WAITING, ResponsibilityLifecycleState.READY, ResponsibilityLifecycleState.BLOCKED).forEach { state ->
            val result = ResponsibilityKernel.reduce(record(state), ResponsibilityObservation.DeadlineReached(now), wait(), now)
            assertEquals(ResponsibilityCoordinationResult.TransitionProposal(ResponsibilityLifecycleState.EXPIRED, ResponsibilityDecisionReasonCode.DEADLINE_PASSED), result)
        }
    }

    @Test
    fun `3 - DeadlineReached is rejected, never coerced, when EXPIRED is not a legal edge`() {
        listOf(ResponsibilityLifecycleState.ACTING, ResponsibilityLifecycleState.VERIFYING).forEach { state ->
            val result = ResponsibilityKernel.reduce(record(state), ResponsibilityObservation.DeadlineReached(now), wait(), now)
            assertEquals(ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.ILLEGAL_EDGE), result)
        }
    }

    // --- Invariant C: UNKNOWN never authorizes a blind retry ---------------

    @Test
    fun `4 - UNKNOWN verification maps to BLOCKED, never produces an ActionRequested or an ACTING proposal`() {
        val result = ResponsibilityKernel.reduce(
            record(ResponsibilityLifecycleState.VERIFYING),
            ResponsibilityObservation.VerificationResultObserved(ResponsibilityVerificationOutcome.UNKNOWN, now),
            wait(), now,
        )
        assertEquals(ResponsibilityCoordinationResult.TransitionProposal(ResponsibilityLifecycleState.BLOCKED, ResponsibilityDecisionReasonCode.VERIFICATION_UNKNOWN), result)
        assertFalse(result is ResponsibilityCoordinationResult.ActionRequested)
    }

    @Test
    fun `5 - TERMINAL_FAILURE verification maps to FAILED, never a retryable state`() {
        val result = ResponsibilityKernel.reduce(
            record(ResponsibilityLifecycleState.VERIFYING),
            ResponsibilityObservation.VerificationResultObserved(ResponsibilityVerificationOutcome.TERMINAL_FAILURE, now),
            wait(), now,
        )
        assertEquals(ResponsibilityCoordinationResult.TransitionProposal(ResponsibilityLifecycleState.FAILED, ResponsibilityDecisionReasonCode.VERIFICATION_TERMINAL_FAILURE), result)
    }

    @Test
    fun `6 - a verification observation received outside VERIFYING is always ILLEGAL_EDGE, never guessed at`() {
        ResponsibilityLifecycleState.entries.filterNot { it.isTerminal || it == ResponsibilityLifecycleState.VERIFYING }.forEach { state ->
            val result = ResponsibilityKernel.reduce(
                record(state),
                ResponsibilityObservation.VerificationResultObserved(ResponsibilityVerificationOutcome.SUCCESS, now),
                wait(), now,
            )
            assertEquals(ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.ILLEGAL_EDGE), result)
        }
    }

    // --- §15 — the critical PA-1A-mapping audit: eligibility, not dispatch -

    @Test
    fun `7 - VERIFYING plus RETRYABLE_FAILURE proposes ACTING as target lifecycle eligibility only -- never implies a dispatch`() {
        // § PA-1B §15 — the exact PA-1A mapping under audit. Reduction alone
        // must never imply a second action was actually dispatched: the
        // result is a bare TransitionProposal value, structurally distinct
        // from ActionRequested (what a future store would need to also see
        // before it could even consider dispatching anything).
        val result = ResponsibilityKernel.reduce(
            record(ResponsibilityLifecycleState.VERIFYING),
            ResponsibilityObservation.VerificationResultObserved(ResponsibilityVerificationOutcome.RETRYABLE_FAILURE, now),
            wait(), now,
        )
        val proposal = result as? ResponsibilityCoordinationResult.TransitionProposal ?: fail("expected TransitionProposal, got $result")
        assertEquals(ResponsibilityLifecycleState.ACTING, proposal.to)
        assertEquals(ResponsibilityDecisionReasonCode.VERIFICATION_RETRYABLE_FAILURE, proposal.reasonCode)
        assertFalse(result is ResponsibilityCoordinationResult.ActionRequested)
        // PA-1A semantics unchanged: the same mapping `reduce` relies on.
        assertEquals(ResponsibilityLifecycleState.ACTING, ResponsibilityLifecycle.targetStateForVerification(ResponsibilityLifecycleState.VERIFYING, ResponsibilityVerificationOutcome.RETRYABLE_FAILURE))
    }

    // --- Invariant D: illegal edges rejected deterministically -------------

    @Test
    fun `8 - ABORT proposes FAILED when legal, is rejected ILLEGAL_EDGE when not`() {
        val legal = ResponsibilityKernel.reduce(record(ResponsibilityLifecycleState.ACTING), ResponsibilityObservation.RecheckDue(now), abort(), now)
        assertEquals(ResponsibilityCoordinationResult.TransitionProposal(ResponsibilityLifecycleState.FAILED, ResponsibilityDecisionReasonCode.ABORTED), legal)

        val illegal = ResponsibilityKernel.reduce(record(ResponsibilityLifecycleState.WAITING), ResponsibilityObservation.RecheckDue(now), abort(), now)
        assertEquals(ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.ILLEGAL_EDGE), illegal)
    }

    // --- Invariant E: RECHECK_AT bounds, both directions --------------------

    @Test
    fun `9 - RECHECK_AT is honored only with a genuine strictly-future timestamp`() {
        val result = ResponsibilityKernel.reduce(record(ResponsibilityLifecycleState.WAITING), ResponsibilityObservation.RecheckDue(now), recheckAt(now + 60_000), now)
        assertEquals(ResponsibilityCoordinationResult.RecheckRequested(now + 60_000, ResponsibilityDecisionReasonCode.RECHECK_SCHEDULED), result)
    }

    @Test
    fun `10 - RECHECK_AT is rejected -- never an immediate recheck-now loop -- for now, the past, or a missing timestamp`() {
        listOf(now, now - 1_000, null).forEach { at ->
            val result = ResponsibilityKernel.reduce(record(ResponsibilityLifecycleState.WAITING), ResponsibilityObservation.RecheckDue(now), recheckAt(at), now)
            assertEquals(ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.INVALID_RECHECK_TIMESTAMP), result)
        }
    }

    // --- Invariants F/G: ACT/VERIFY are requests only -----------------------

    @Test
    fun `11 - ACT reduces to a pure ActionRequested directive -- never a bundled lifecycle transition, zero side effect`() {
        val record = record(ResponsibilityLifecycleState.READY)
        val results = (1..5).map { ResponsibilityKernel.reduce(record, ResponsibilityObservation.RecheckDue(now), act(), now) }
        assertTrue(results.all { it == ResponsibilityCoordinationResult.ActionRequested(ResponsibilityDecisionReasonCode.READY_TO_ACT) })
    }

    @Test
    fun `12 - VERIFY reduces to a pure VerificationRequested directive -- never manufactures SUCCESS`() {
        val result = ResponsibilityKernel.reduce(record(ResponsibilityLifecycleState.ACTING), ResponsibilityObservation.RecheckDue(now), verify(), now)
        assertEquals(ResponsibilityCoordinationResult.VerificationRequested(ResponsibilityDecisionReasonCode.AWAITING_VERIFICATION), result)
        assertFalse(result is ResponsibilityCoordinationResult.TransitionProposal)
    }

    // --- Invariant H: COMPLETE requires authoritative verification ----------

    @Test
    fun `13 - a policy deciding COMPLETE is always rejected -- only authoritative verification can complete`() {
        ResponsibilityLifecycleState.entries.filterNot { it.isTerminal }.forEach { state ->
            val result = ResponsibilityKernel.reduce(record(state), ResponsibilityObservation.RecheckDue(now), complete(), now)
            assertEquals(ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.COMPLETE_REQUIRES_AUTHORITATIVE_VERIFICATION), result)
        }
    }

    @Test
    fun `14 - only a genuine VerificationResultObserved SUCCESS can propose COMPLETED`() {
        val result = ResponsibilityKernel.reduce(
            record(ResponsibilityLifecycleState.VERIFYING),
            ResponsibilityObservation.VerificationResultObserved(ResponsibilityVerificationOutcome.SUCCESS, now),
            wait(), now,
        )
        assertEquals(ResponsibilityCoordinationResult.TransitionProposal(ResponsibilityLifecycleState.COMPLETED, ResponsibilityDecisionReasonCode.VERIFICATION_SUCCESS), result)
    }

    // --- §21: ASK_USER never completes ---------------------------------------

    @Test
    fun `15 - ASK_USER is a pure directive that can never itself complete the responsibility`() {
        val result = ResponsibilityKernel.reduce(record(ResponsibilityLifecycleState.BLOCKED), ResponsibilityObservation.RecheckDue(now), askUser(), now)
        assertEquals(ResponsibilityCoordinationResult.AskUserRequested(ResponsibilityDecisionReasonCode.NEEDS_USER_INPUT), result)
        assertFalse(result is ResponsibilityCoordinationResult.TransitionProposal)
    }

    // --- §20: restore never duplicates / never specially re-dispatches ------

    @Test
    fun `16 - ProcessRestored is reduced identically to any other deferred observation -- no special create or redispatch behavior`() {
        val record = record(ResponsibilityLifecycleState.READY)
        val restored = ResponsibilityKernel.reduce(record, ResponsibilityObservation.ProcessRestored(now), act(), now)
        val external = ResponsibilityKernel.reduce(record, ResponsibilityObservation.ExternalEventObserved(ResponsibilityTriggerIdentifier.NEXT_ALARM, now), act(), now)
        assertEquals(restored, external)
        assertTrue(restored is ResponsibilityCoordinationResult.ActionRequested)
    }

    // --- §22: fail-closed on missing/empty context --------------------------

    @Test
    fun `17 - a fail-closed fake policy given no typed context never spontaneously fabricates ACT`() {
        val policy = object : ResponsibilityDecisionPolicy {
            override fun decide(record: ResponsibilityRecord, observation: ResponsibilityObservation, context: ResponsibilityContextSnapshot, now: Long) =
                ResponsibilityPolicyResult(ResponsibilityDecision.WAIT, ResponsibilityDecisionReasonCode.OBSERVATION_INSUFFICIENT)
        }
        val record = record(ResponsibilityLifecycleState.WAITING)
        val observation = ResponsibilityObservation.ExternalEventObserved(ResponsibilityTriggerIdentifier.FIRST_UNLOCK, now)
        val policyResult = policy.decide(record, observation, NoResponsibilityContext, now)
        val result = ResponsibilityKernel.reduce(record, observation, policyResult, now)
        assertEquals(ResponsibilityCoordinationResult.NoOp, result)
    }

    // --- Invariant I: one observation -> at most one bounded result ---------

    @Test
    fun `18 - reduce is a deterministic total function returning exactly one result per call`() {
        val record = record(ResponsibilityLifecycleState.READY)
        val observation = ResponsibilityObservation.ExternalEventObserved(ResponsibilityTriggerIdentifier.NEXT_ALARM, now)
        val policyResult = wait()
        val a = ResponsibilityKernel.reduce(record, observation, policyResult, now)
        val b = ResponsibilityKernel.reduce(record, observation, policyResult, now)
        assertEquals(a, b)
    }

    @Test
    fun `19 - reduce never throws for any combination of non-terminal state, observation, and policy decision`() {
        val nonTerminalStates = ResponsibilityLifecycleState.entries.filterNot { it.isTerminal }
        val decisions = ResponsibilityDecision.entries.map {
            ResponsibilityPolicyResult(it, ResponsibilityDecisionReasonCode.OBSERVATION_INSUFFICIENT, recheckAtMs = now + 1_000)
        }
        nonTerminalStates.forEach { state ->
            allObservations.forEach { obs ->
                decisions.forEach { policy ->
                    ResponsibilityKernel.reduce(record(state), obs, policy, now) // must complete without throwing
                }
            }
        }
    }

    // --- WAIT -> NoOp, the baseline no-action mapping ------------------------

    @Test
    fun `20 - WAIT always reduces to NoOp, independent of lifecycle state`() {
        ResponsibilityLifecycleState.entries.filterNot { it.isTerminal }.forEach { state ->
            val result = ResponsibilityKernel.reduce(record(state), ResponsibilityObservation.RecheckDue(now), wait(), now)
            assertEquals(ResponsibilityCoordinationResult.NoOp, result)
        }
    }
}
