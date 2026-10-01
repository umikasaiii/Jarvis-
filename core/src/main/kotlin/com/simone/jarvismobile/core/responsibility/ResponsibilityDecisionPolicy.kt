package com.simone.jarvismobile.core.responsibility

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1B §10-11. Bounded, closed-world
 * reason code for a [ResponsibilityDecisionPolicy]'s decision — never free
 * text, so every decision a policy can ever make is enumerable and
 * journal-safe by construction (mirrors [ResponsibilityTransitionRejection]'s
 * own discipline).
 */
enum class ResponsibilityDecisionReasonCode {
    OBSERVATION_INSUFFICIENT,
    READY_TO_ACT,
    ACTION_ALREADY_DISPATCHED,
    AWAITING_VERIFICATION,
    VERIFICATION_SUCCESS,
    VERIFICATION_RETRYABLE_FAILURE,
    VERIFICATION_TERMINAL_FAILURE,
    VERIFICATION_UNKNOWN,
    DEADLINE_PASSED,
    RECHECK_SCHEDULED,
    CAPABILITY_MISSING,
    NEEDS_USER_INPUT,
    ABORTED,
}

/**
 * § PA-1B §10-11. The bounded output of a [ResponsibilityDecisionPolicy] —
 * [decision] is the PA-1A taxonomy ([ResponsibilityDecision]), [reasonCode]
 * is always present (never a free-text reason), and [recheckAtMs] is only
 * meaningful when [decision] is [ResponsibilityDecision.RECHECK_AT] (every
 * other decision must leave it `null`; [ResponsibilityKernel] validates this
 * independently of what a policy actually sets — see invariant E).
 */
data class ResponsibilityPolicyResult(
    val decision: ResponsibilityDecision,
    val reasonCode: ResponsibilityDecisionReasonCode,
    val recheckAtMs: Long? = null,
)

/**
 * § PA-1B §10-11. A pure, deterministic domain policy: given the current
 * [ResponsibilityRecord], the [ResponsibilityObservation] that triggered
 * re-evaluation, a typed [ResponsibilityContextSnapshot], and the evaluation
 * timestamp, decides what [ResponsibilityDecision] applies.
 *
 * Deliberately NOT given any Android callback/`Runnable`/`Intent`, any
 * arbitrary tool name, or any executable string — a policy can only ever
 * return one of the closed-world [ResponsibilityDecision] values, never
 * something that could itself perform a side effect (that boundary is
 * enforced one layer up, by [ResponsibilityKernel] — see §14).
 *
 * PA-1B ships this interface plus deterministic test/fake implementations
 * only. It does NOT hard-code any Morning Briefing product policy (e.g.
 * "NEXT_ALARM observed → ACT") — `MorningAssistancePolicy` is explicitly
 * PA-2 territory (§65.1).
 */
interface ResponsibilityDecisionPolicy {
    fun decide(
        record: ResponsibilityRecord,
        observation: ResponsibilityObservation,
        context: ResponsibilityContextSnapshot,
        now: Long,
    ): ResponsibilityPolicyResult
}
