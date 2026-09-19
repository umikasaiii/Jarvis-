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

    /** The suggestion content was composed — not yet attempted for delivery ("PREPARED" in the Proactivity Reliability Closure Audit's vocabulary). */
    GENERATED,

    /**
     * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
     * WORK PACKAGE A (P0-6). "DISPATCH_INTENT" in the audit's vocabulary —
     * [com.simone.jarvismobile.core.proactive.ProactiveKind]'s notification
     * post has been committed to and is about to be (or was just) attempted.
     * **Never stale-takeover-eligible by age alone** (see
     * [ProactiveOccurrenceReconciler.decide] below) — the audit's own
     * correction to the superseded MICRO-PATCH 14.2.2/PASSAGGIO 14.1
     * conclusion that "expired DELIVERY_PENDING is safe to retry": the
     * Android notification call is OUTSIDE the database transaction, so an
     * age-based reclaim here could produce a genuine duplicate visible
     * notification if the original call actually succeeded right before a
     * crash. A row stuck here forever without human/manual reconciliation
     * is the deliberate, explicit trade-off ("trades possible omission for
     * no automatic duplicate").
     */
    DELIVERY_PENDING,

    /** The visible delivery genuinely happened ("POSTED" in the audit's vocabulary). Terminal — never retried. */
    DELIVERED,

    /** A failure occurred before any visible side effect — safe to retry/take over. */
    FAILED_RETRYABLE,

    /** A failure occurred that must never be retried automatically. Terminal. */
    FAILED_FINAL,

    /**
     * § PROACTIVITY RELIABILITY CLOSURE WORK PACKAGE A — the Android
     * notification API call was never reached (or a definite pre-dispatch
     * preflight check — POST_NOTIFICATIONS missing, notifications globally
     * disabled, channel blocked — rejected it) BEFORE anything was sent.
     * Proven no-effect, so — unlike [DELIVERY_PENDING] — this IS always
     * safe to retry once the blocking condition may have changed (e.g. the
     * user just granted the permission).
     */
    BLOCKED_PERMISSION,

    /**
     * § PROACTIVITY RELIABILITY CLOSURE WORK PACKAGE A. The Android
     * notification API call was made and either threw or the calling
     * process disappeared before the outcome could be durably recorded —
     * genuinely unknown whether the user ever saw anything. Per the
     * one-shot contract's crash-boundary strengthening ("an unknown outcome
     * is never automatically retried"), this behaves exactly like
     * [DELIVERY_PENDING] in [ProactiveOccurrenceReconciler.decide] — never
     * a blind retry — kept as its own distinct value purely for honest
     * diagnostics (so a human/future reconciliation path can tell "we
     * never even tried" apart from "we tried and don't know what happened").
     */
    UNKNOWN_EFFECT,
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
 *
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE A (P0-6, superseding MICRO-PATCH 14.2.2/PASSAGGIO 14.1's
 * "expired DELIVERY_PENDING is safe to retry" conclusion — see
 * `docs/JARVIS_PROACTIVITY_RELIABILITY_CLOSURE_AUDIT.md` §2.3): only
 * [ProactiveOccurrenceState.CLAIMED]/[ProactiveOccurrenceState.GENERATED]
 * (no dispatch intent committed yet) are ever staleness-based takeover
 * candidates. [ProactiveOccurrenceState.DELIVERY_PENDING] and
 * [ProactiveOccurrenceState.UNKNOWN_EFFECT] are UNKNOWN-outcome states —
 * they NEVER become retryable merely because time passed, at any age,
 * because the Android notification call they represent is outside this
 * database's transaction and may have genuinely succeeded.
 * [ProactiveOccurrenceState.BLOCKED_PERMISSION] is proven NO-EFFECT
 * (rejected before ever reaching Android), so — like
 * [ProactiveOccurrenceState.FAILED_RETRYABLE] — it is always immediately
 * retryable, unconditional on age.
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
            // §P0-6: an ambiguous in-flight/unknown-outcome dispatch is never
            // blindly retried, no matter how old — the deliberate at-most-once
            // trade-off (§4.1 of the audit: "trades possible omission for no
            // automatic duplicate").
            ProactiveOccurrenceState.DELIVERY_PENDING -> OccurrenceClaimOutcome.AlreadyOwned(existingState)
            ProactiveOccurrenceState.UNKNOWN_EFFECT -> OccurrenceClaimOutcome.AlreadyOwned(existingState)
            ProactiveOccurrenceState.FAILED_RETRYABLE -> OccurrenceClaimOutcome.TakeoverAllowed
            ProactiveOccurrenceState.BLOCKED_PERMISSION -> OccurrenceClaimOutcome.TakeoverAllowed
            ProactiveOccurrenceState.CLAIMED,
            ProactiveOccurrenceState.GENERATED,
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

    /**
     * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
     * WORK PACKAGE A §15: evening joins the SAME durable occurrence
     * authority morning already has (P1-6 — "evening has no durable Room
     * claim", closed here). Keyed by the DELIVERY date (today, evening),
     * never the agenda TARGET date (tomorrow) — the audit's own
     * distinction: `EVENING_DIGEST:<deliveryDate>` with the agenda target
     * date stored/rendered separately by the composer (Work Package C
     * territory, not this key).
     */
    fun eveningDigest(deliveryLocalDate: LocalDate): String = "${ProactiveKind.EVENING_DIGEST}:$deliveryLocalDate"
}
