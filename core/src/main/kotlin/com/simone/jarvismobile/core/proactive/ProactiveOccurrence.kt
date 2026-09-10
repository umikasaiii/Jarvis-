package com.simone.jarvismobile.core.proactive

import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.1 §F. What is durably
 * known about one logical Morning Brief occurrence's progress — never
 * collapsed to a boolean (§A: "Do not solve this with an in-memory
 * Boolean"). Mirrors the shape (not the table) of
 * `com.simone.jarvismobile.automation.rule.OccurrenceCommitState` from
 * PASSAGGIO 8/8.1 — the closest existing pattern for "durable side-effect
 * state machine with atomic claim" — without merging Morning Brief's
 * occurrences into that unrelated rule-automation table (§T: Morning Brief
 * coordination owns only its OWN logical occurrence lifecycle, never a
 * second copy of domain data, and never someone else's table either).
 */
enum class ProactiveOccurrenceState {
    /** Atomically claimed by exactly one trigger — no generation/delivery attempted yet. */
    CLAIMED,

    /** The suggestion content was composed — not yet attempted for delivery. */
    GENERATED,

    /** [com.simone.jarvismobile.core.proactive.ProactiveKind]'s notification post has been (or is about to be) attempted. */
    DELIVERY_PENDING,

    /** The visible delivery genuinely happened. Terminal — never retried. */
    DELIVERED,

    /** A failure occurred before any visible side effect — safe to retry/take over. */
    FAILED_RETRYABLE,

    /** A failure occurred that must never be retried automatically. Terminal. */
    FAILED_FINAL,
    ;

    val isTerminal: Boolean get() = this == DELIVERED || this == FAILED_FINAL
}

/** The result of attempting to claim one logical occurrence. */
sealed interface OccurrenceClaimOutcome {
    /** No prior row existed — this caller now owns generation/delivery. */
    data object Claimed : OccurrenceClaimOutcome

    /** A prior CLAIMED/GENERATED/DELIVERY_PENDING row went stale (§M crash windows 1-2) or a prior attempt failed retryably — this caller takes over the SAME occurrence identity. */
    data object TakeoverAllowed : OccurrenceClaimOutcome

    /** Someone else already owns this occurrence (in flight or done) — this caller must be a no-op. */
    data class AlreadyOwned(val state: ProactiveOccurrenceState) : OccurrenceClaimOutcome
}

/**
 * § PASSAGGIO 14.1 §F/§I/§M — the pure reconciliation POLICY: given what is
 * durably known about an existing occurrence row (if any), decide whether a
 * NEW trigger may claim it fresh, take over a stale/failed one, or must
 * stand down. Never invents "delivered" and never blindly retries an
 * in-flight (potentially UNKNOWN-outcome) attempt before [staleAfterMs] has
 * passed (§I: "SIDE EFFECT UNKNOWN → NO BLIND RETRY").
 *
 * [ProactiveOccurrenceStore] (`app/`) is what makes the decision this
 * function returns actually STICK atomically (a conditional Room
 * insert/update) — this function only decides what SHOULD happen assuming
 * the read it was given is accurate; the store re-validates atomically
 * before ever writing.
 */
object ProactiveOccurrenceReconciler {
    fun decide(
        existingState: ProactiveOccurrenceState?,
        existingClaimedAtMs: Long?,
        now: Long,
        staleAfterMs: Long,
    ): OccurrenceClaimOutcome {
        if (existingState == null) return OccurrenceClaimOutcome.Claimed
        return when (existingState) {
            ProactiveOccurrenceState.DELIVERED -> OccurrenceClaimOutcome.AlreadyOwned(existingState)
            ProactiveOccurrenceState.FAILED_FINAL -> OccurrenceClaimOutcome.AlreadyOwned(existingState)
            ProactiveOccurrenceState.FAILED_RETRYABLE -> OccurrenceClaimOutcome.TakeoverAllowed
            ProactiveOccurrenceState.CLAIMED,
            ProactiveOccurrenceState.GENERATED,
            ProactiveOccurrenceState.DELIVERY_PENDING,
            -> {
                val age = now - (existingClaimedAtMs ?: now)
                if (age >= staleAfterMs) OccurrenceClaimOutcome.TakeoverAllowed
                else OccurrenceClaimOutcome.AlreadyOwned(existingState)
            }
        }
    }
}

/**
 * § PASSAGGIO 14.1 §D. The stable logical occurrence key — feature + local
 * logical date, nothing else (no `System.currentTimeMillis()`, no random
 * UUID, no worker/process id — all explicitly forbidden by §D since they
 * would create a NEW logical occurrence for the same morning). Deliberately
 * the EXACT SAME string format [ProactiveComposer.morningDigest] already
 * uses for its own `dedupKey` (`"${ProactiveKind.MORNING_DIGEST}:$date"`) —
 * one shared formula, not two independent literals that could drift; see
 * `ProactiveOccurrenceKeyTest`'s explicit drift guard.
 */
object ProactiveOccurrenceKey {
    fun morningDigest(logicalDate: LocalDate): String = "${ProactiveKind.MORNING_DIGEST}:$logicalDate"

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 14.2. Keyed by the
     * TARGET local date being warned about (tomorrow, at evaluation time —
     * see [com.simone.jarvismobile.core.weather.WeatherAlertPolicy.targetDateFor]),
     * never by the evaluation date itself and never by hazard tier: at most
     * ONE weather-alert notification exists per logical target day,
     * regardless of how many times the evening window re-evaluates the
     * forecast or how the hazard classification shifts between
     * evaluations — the DoD's own "duplicate evaluation same target day →
     * one occurrence" requirement, taken literally. A stronger forecast
     * found before this occurrence is claimed/delivered naturally wins
     * (each evaluation reclassifies from fresh data); a stronger forecast
     * discovered AFTER today's alert has already been delivered does NOT
     * re-notify — see [com.simone.jarvismobile.proactive.ProactiveManager]'s
     * own doc comment for why extending PASSAGGIO 14.1's terminal DELIVERED
     * state to allow post-delivery escalation was deliberately left out of
     * this pass.
     */
    fun weatherAlert(targetLocalDate: LocalDate): String = "${ProactiveKind.WEATHER_ALERT}:$targetLocalDate"
}
