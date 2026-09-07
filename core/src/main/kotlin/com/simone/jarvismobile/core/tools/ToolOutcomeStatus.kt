package com.simone.jarvismobile.core.tools

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 1 (Structured Tool Results
 * + Outcome Taxonomy Foundation). The taxonomy the current binary
 * `ToolResult.Success`/`ToolResult.Failure` (see `Tool.kt`) cannot express on
 * its own: JARVIS currently treats "real data", "no data", "stale data",
 * "permission missing", "source failure" and "tool failure" as at most two
 * buckets (success/failure) — this enum is the missing distinction, attached
 * as optional evidence (see [StructuredToolResult]) rather than replacing
 * either existing case.
 *
 * Scoped to the SOURCE/CAPABILITY/TOOL/GROUNDING boundary this phase owns —
 * deliberately does NOT include `SemanticFailure`/`ModelFailure` (those
 * belong to the semantic classifier/reasoning boundary, a different owner).
 */
enum class ToolOutcomeStatus {
    /** The tool ran, the source was available, and it found real data. */
    SUCCESS_DATA,

    /**
     * The tool ran, the source was available, the requested coverage was
     * sufficient, and the real answer is "nothing there" — a genuine result,
     * never to be confused with [DATA_UNAVAILABLE]/[SOURCE_FAILURE]. Must
     * NEVER be produced from an ambiguous failure (§7/§18).
     */
    SUCCESS_EMPTY,

    /** The source is reachable but does not (yet) have the requested data — a coverage gap, not a crash. */
    DATA_UNAVAILABLE,

    /** The user has not granted (or has revoked) the permission this data requires — distinct from any other failure. */
    PERMISSION_MISSING,

    /** Real data exists and is being returned, but it is known-old — see [StructuredToolResult.observedAt]/[StructuredToolResult.retrievedAt]. */
    STALE,

    /** The upstream data source itself failed (network error, provider outage, corrupt response). */
    SOURCE_FAILURE,

    /** The tool/capability itself failed independent of any external source (a bug, a crash, a timeout). */
    TOOL_FAILURE,

    /** Some sub-parts of a multi-part request succeeded and some did not — see [StructuredToolResult.partialFailureReasons]. */
    PARTIAL,
}

/**
 * § PASSAGGIO 1 §17 — coarse bucketing for a consumer that only needs to know
 * how much to trust an outcome, never a substitute for reading the real
 * [ToolOutcomeStatus]. A [ToolOutcomeTier.UNAVAILABLE] result must reach the
 * consumer as its real status, never be silently turned into a generic LLM
 * answer.
 */
enum class ToolOutcomeTier { NORMAL, DEGRADED, UNAVAILABLE }

fun ToolOutcomeStatus.tier(): ToolOutcomeTier = when (this) {
    ToolOutcomeStatus.SUCCESS_DATA, ToolOutcomeStatus.SUCCESS_EMPTY -> ToolOutcomeTier.NORMAL
    ToolOutcomeStatus.STALE, ToolOutcomeStatus.PARTIAL -> ToolOutcomeTier.DEGRADED
    ToolOutcomeStatus.DATA_UNAVAILABLE, ToolOutcomeStatus.PERMISSION_MISSING,
    ToolOutcomeStatus.SOURCE_FAILURE, ToolOutcomeStatus.TOOL_FAILURE,
    -> ToolOutcomeTier.UNAVAILABLE
}
