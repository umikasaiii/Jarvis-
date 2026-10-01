package com.simone.jarvismobile.core.responsibility

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1B §16. The bounded, pure
 * coordination result of reducing one [ResponsibilityObservation] against one
 * [ResponsibilityRecord] and one [ResponsibilityPolicyResult]. Exactly one
 * case applies per call — structurally impossible to confuse "the kernel
 * says ACT" with "the action happened", or "the kernel says COMPLETE" with
 * "the goal was verified": [ActionRequested]/[VerificationRequested] are
 * directives only (§14/§F/§G — a FUTURE execution adapter, not this type,
 * decides whether/when to actually dispatch something), and the only way to
 * reach [TransitionProposal] toward [ResponsibilityLifecycleState.COMPLETED]
 * is an authoritative [ResponsibilityObservation.VerificationResultObserved]
 * (§H — never a policy merely *deciding* `COMPLETE`).
 *
 * "Proposal" instead of "Applied": [ResponsibilityKernel] never mutates Room
 * or any other durable store itself (§13/§17-18) — a future store (PA-2+)
 * decides whether/how to commit a [TransitionProposal], exactly as
 * [ResponsibilityLifecycle.transition] already does not commit itself.
 */
sealed interface ResponsibilityCoordinationResult {
    /** The observation was reduced but implies no action/transition — e.g. a policy decision of `WAIT`. */
    data object NoOp : ResponsibilityCoordinationResult

    /** A lifecycle transition is eligible, pending a future store committing it. Never implies the transition already happened. */
    data class TransitionProposal(val to: ResponsibilityLifecycleState, val reasonCode: ResponsibilityDecisionReasonCode) : ResponsibilityCoordinationResult

    /** A request to perform an action — a directive only; never implies the side effect occurred (§F). */
    data class ActionRequested(val reasonCode: ResponsibilityDecisionReasonCode) : ResponsibilityCoordinationResult

    /** A request to verify a prior action — a directive only; never manufactures a `SUCCESS` outcome itself (§G). */
    data class VerificationRequested(val reasonCode: ResponsibilityDecisionReasonCode) : ResponsibilityCoordinationResult

    /** A request to re-evaluate again no earlier than [atMs] — always validated to be a real future timestamp (§E). */
    data class RecheckRequested(val atMs: Long, val reasonCode: ResponsibilityDecisionReasonCode) : ResponsibilityCoordinationResult

    /** A request for a human decision — a directive only; never itself completes the responsibility (§21). */
    data class AskUserRequested(val reasonCode: ResponsibilityDecisionReasonCode) : ResponsibilityCoordinationResult

    /** The reduction was refused outright — never a partially-applied result. */
    data class Rejected(val rejection: ResponsibilityReductionRejection) : ResponsibilityCoordinationResult
}

/** Bounded, closed-world reason a reduction was [ResponsibilityCoordinationResult.Rejected] — never a free-text message. */
enum class ResponsibilityReductionRejection {
    TERMINAL_STATE_IMMUTABLE,
    ILLEGAL_EDGE,
    COMPLETE_REQUIRES_AUTHORITATIVE_VERIFICATION,
    INVALID_RECHECK_TIMESTAMP,
}

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1B §12-13. The pure, deterministic
 * reducer: `(record, observation, policyResult, now) -> ResponsibilityCoordinationResult`.
 *
 * Never mutates Room or any other durable state, never performs an action
 * (§13/§14) — calling [reduce] any number of times, in any order, with the
 * same inputs, has no observable effect beyond the single value it returns.
 *
 * Enforces invariants A-I **independently of the domain policy** — a
 * misbehaving or future [ResponsibilityDecisionPolicy] can never cause this
 * reducer to violate one of them:
 *
 * - **A** — a terminal [ResponsibilityRecord.lifecycleState] can never be
 *   reopened by any later observation, checked first and unconditionally.
 * - **B** — a [ResponsibilityObservation.DeadlineReached] on a non-terminal
 *   record MAY propose [ResponsibilityLifecycleState.EXPIRED], but only when
 *   that is a legal [ResponsibilityLifecycle] edge from the current state —
 *   "may", not "always" (e.g. illegal from `ACTING`/`VERIFYING`).
 * - **C** — [ResponsibilityVerificationOutcome.UNKNOWN] never auto-generates
 *   an `ACT`/retry: the verification branch below defers entirely to
 *   [ResponsibilityLifecycle.targetStateForVerification], which already maps
 *   `UNKNOWN` to `BLOCKED`, never `ACTING`.
 * - **D** — an illegal lifecycle edge is always [ResponsibilityCoordinationResult.Rejected]
 *   deterministically, never silently coerced to a legal one.
 * - **E** — a `RECHECK_AT` policy decision is only honored when
 *   [ResponsibilityPolicyResult.recheckAtMs] is a genuine, strictly-future
 *   timestamp relative to [now] — never an "immediate recheck" loop.
 * - **F** — `ACT` policy decisions reduce to [ResponsibilityCoordinationResult.ActionRequested]
 *   only — never bundled with a lifecycle transition, so a store consuming
 *   only this result cannot accidentally mutate the record from an `ACT`.
 * - **G** — `VERIFY` policy decisions reduce to [ResponsibilityCoordinationResult.VerificationRequested]
 *   only, for the same reason.
 * - **H** — a `COMPLETE` policy decision is always [ResponsibilityCoordinationResult.Rejected]
 *   with [ResponsibilityReductionRejection.COMPLETE_REQUIRES_AUTHORITATIVE_VERIFICATION]
 *   — the ONLY path to a `COMPLETED` [ResponsibilityCoordinationResult.TransitionProposal]
 *   is the [ResponsibilityObservation.VerificationResultObserved] branch below,
 *   driven solely by [ResponsibilityVerificationOutcome.SUCCESS].
 * - **I** — [reduce] returns exactly one [ResponsibilityCoordinationResult]
 *   per call; there is no internal loop, retry, or multi-value return.
 */
object ResponsibilityKernel {

    fun reduce(
        record: ResponsibilityRecord,
        observation: ResponsibilityObservation,
        policyResult: ResponsibilityPolicyResult,
        now: Long,
    ): ResponsibilityCoordinationResult {
        // Invariant A — checked first, unconditionally, independent of the
        // observation or policy: a terminal state is never reopened.
        if (record.lifecycleState.isTerminal) {
            return ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.TERMINAL_STATE_IMMUTABLE)
        }

        // Invariant B — a deadline observation is a kernel-level fact, reduced
        // the same way regardless of what the policy decided for it.
        if (observation is ResponsibilityObservation.DeadlineReached) {
            return if (ResponsibilityLifecycle.isValidTransition(record.lifecycleState, ResponsibilityLifecycleState.EXPIRED)) {
                ResponsibilityCoordinationResult.TransitionProposal(ResponsibilityLifecycleState.EXPIRED, ResponsibilityDecisionReasonCode.DEADLINE_PASSED)
            } else {
                ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.ILLEGAL_EDGE)
            }
        }

        // Invariants C/D/G/H (verification path) — an authoritative
        // verification result is reduced solely via the existing PA-1A
        // mapping, never via the policy's own decision for this observation.
        if (observation is ResponsibilityObservation.VerificationResultObserved) {
            if (record.lifecycleState != ResponsibilityLifecycleState.VERIFYING) {
                return ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.ILLEGAL_EDGE)
            }
            val target = ResponsibilityLifecycle.targetStateForVerification(ResponsibilityLifecycleState.VERIFYING, observation.outcome)
                ?: return ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.ILLEGAL_EDGE)
            return ResponsibilityCoordinationResult.TransitionProposal(target, reasonCodeForVerification(observation.outcome))
        }

        // Every other observation (ExternalEventObserved, RecheckDue,
        // ProcessRestored, CapabilityChanged) defers entirely to the policy's
        // own decision — §20: ProcessRestored carries no special "re-dispatch"
        // behavior here, it is reduced identically to any other observation.
        return reduceDecision(record, policyResult, now)
    }

    private fun reduceDecision(
        record: ResponsibilityRecord,
        policyResult: ResponsibilityPolicyResult,
        now: Long,
    ): ResponsibilityCoordinationResult = when (policyResult.decision) {
        ResponsibilityDecision.WAIT -> ResponsibilityCoordinationResult.NoOp

        // Invariant F — a request only, never a bundled transition.
        ResponsibilityDecision.ACT -> ResponsibilityCoordinationResult.ActionRequested(policyResult.reasonCode)

        // Invariant G — a request only, never a bundled transition, never
        // manufactures SUCCESS.
        ResponsibilityDecision.VERIFY -> ResponsibilityCoordinationResult.VerificationRequested(policyResult.reasonCode)

        // Invariant E — recheckAtMs must be a genuine future timestamp.
        ResponsibilityDecision.RECHECK_AT -> {
            val at = policyResult.recheckAtMs
            if (at == null || at <= now) {
                ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.INVALID_RECHECK_TIMESTAMP)
            } else {
                ResponsibilityCoordinationResult.RecheckRequested(at, policyResult.reasonCode)
            }
        }

        // §21 — a directive only, never itself completes the responsibility.
        ResponsibilityDecision.ASK_USER -> ResponsibilityCoordinationResult.AskUserRequested(policyResult.reasonCode)

        // Invariant H — COMPLETE can never be reached by a mere policy
        // decision; only an authoritative VerificationResultObserved(SUCCESS)
        // (handled exclusively in `reduce` above) may propose COMPLETED.
        ResponsibilityDecision.COMPLETE -> ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.COMPLETE_REQUIRES_AUTHORITATIVE_VERIFICATION)

        // ABORT is the one decision that does propose a transition — toward
        // the terminal FAILED state, validated like any other edge.
        ResponsibilityDecision.ABORT -> if (ResponsibilityLifecycle.isValidTransition(record.lifecycleState, ResponsibilityLifecycleState.FAILED)) {
            ResponsibilityCoordinationResult.TransitionProposal(ResponsibilityLifecycleState.FAILED, ResponsibilityDecisionReasonCode.ABORTED)
        } else {
            ResponsibilityCoordinationResult.Rejected(ResponsibilityReductionRejection.ILLEGAL_EDGE)
        }
    }

    private fun reasonCodeForVerification(outcome: ResponsibilityVerificationOutcome): ResponsibilityDecisionReasonCode = when (outcome) {
        ResponsibilityVerificationOutcome.SUCCESS -> ResponsibilityDecisionReasonCode.VERIFICATION_SUCCESS
        ResponsibilityVerificationOutcome.RETRYABLE_FAILURE -> ResponsibilityDecisionReasonCode.VERIFICATION_RETRYABLE_FAILURE
        ResponsibilityVerificationOutcome.TERMINAL_FAILURE -> ResponsibilityDecisionReasonCode.VERIFICATION_TERMINAL_FAILURE
        ResponsibilityVerificationOutcome.UNKNOWN -> ResponsibilityDecisionReasonCode.VERIFICATION_UNKNOWN
    }
}
