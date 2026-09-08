package com.simone.jarvismobile.core.segnale

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §I. The presentation
 * projection for an ACTION/side-effect (as opposed to [DataStatus], which
 * projects a READ) — a tool-call awaiting user confirmation, an automation
 * occurrence, a write in flight. Distinct enum, not a reuse of [DataStatus],
 * because a side effect has no "empty"/"stale"/"partial" reading concept and
 * does have a genuinely different terminal shape (confirmed vs. merely
 * "data present").
 *
 * Naming intentionally generic rather than pinned to one domain enum
 * (`OccurrenceCommitState`, `ExecutionDecision`, a pending tool-call
 * confirmation) — those remain the real runtime owners; this is only ever a
 * mapping target for them, never a replacement.
 */
enum class SideEffectStatus {
    /** Awaiting something before it can proceed — e.g. a tool call awaiting user confirmation. */
    PENDING,

    /** In flight right now. */
    RUNNING,

    /** Completed and its effect is confirmed to have happened. */
    CONFIRMED,

    /** Did not complete, and — where knowable — its effect is known not to have happened. */
    FAILED,

    /**
     * A side effect whose true final state cannot be established from here
     * (e.g. a write that failed after a real network call, so the receiving
     * end may or may not have applied it) — must never be silently folded
     * into [FAILED]/[CONFIRMED]; the honest answer is "we don't know".
     */
    UNKNOWN,
}
