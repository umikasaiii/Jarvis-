package com.simone.jarvismobile.automation.rule

/**
 * What is durably known about one already-attempted occurrence's side
 * effects (§ JARVIS Implementation Master Plan PASSAGGIO 8.1 §2/§3).
 *
 * [com.simone.jarvismobile.core.automation.rule.ExecutionDecision.FIRE] only
 * means the gate allowed an attempt — it says nothing about whether the
 * attempt's side effects actually landed. This is the separate, honest
 * answer to that second question, computed once the attempt is over and
 * persisted alongside the FIRE row so a later durable dedup lookup can tell
 * a safe retry from a dangerous one.
 */
enum class OccurrenceCommitState {
    /** Every action's effect is known to have happened. Never safe to retry. */
    COMMITTED,

    /**
     * No action produced any effect — each one was either deliberately
     * skipped or failed in a way *proven* to have happened before any
     * side-effecting call was attempted. Safe to retry: retrying re-runs
     * exactly nothing that already happened.
     */
    RETRYABLE_NO_EFFECT,

    /**
     * Some effect may have happened and some may not have — a mix of
     * successes and failures, or any failure whose effect is not proven
     * absent (a timeout, an exception mid-call, a tool failure of unknown
     * origin). Re-running the whole occurrence risks duplicating whatever
     * DID land, so this state blocks automatic retry exactly like
     * [COMMITTED] — the two are distinguished only for diagnostics.
     */
    INDETERMINATE,
}

/**
 * Classifies what a completed occurrence's outcomes durably prove.
 *
 * A retry always re-runs *every* action of the rule from scratch — there is
 * no partial-resume anywhere in this engine — so [OccurrenceCommitState.RETRYABLE_NO_EFFECT]
 * requires that NOT ONE action produced an effect: a single [ActionOutcome.Done]
 * mixed with failures already means a blind retry would re-run (and
 * duplicate) that one, which is why that mix classifies as
 * [OccurrenceCommitState.INDETERMINATE], never as retryable.
 */
fun classifyOccurrenceCommitState(outcomes: List<Pair<String, ActionOutcome>>): OccurrenceCommitState {
    val anyDone = outcomes.any { it.second is ActionOutcome.Done }
    val allDone = outcomes.isNotEmpty() && outcomes.all { it.second is ActionOutcome.Done }
    return when {
        allDone -> OccurrenceCommitState.COMMITTED
        anyDone -> OccurrenceCommitState.INDETERMINATE
        outcomes.isNotEmpty() && outcomes.all { it.second.isProvenNoEffect() } ->
            OccurrenceCommitState.RETRYABLE_NO_EFFECT
        else -> OccurrenceCommitState.INDETERMINATE
    }
}

private fun ActionOutcome.isProvenNoEffect(): Boolean = when (this) {
    // Never reached from the branch above (anyDone would already be true),
    // kept here only so this `when` stays exhaustive and honest.
    is ActionOutcome.Done -> false
    is ActionOutcome.Skipped -> true
    is ActionOutcome.Failed -> provenNoEffect
}
