package com.simone.jarvismobile.core.tools

import kotlinx.serialization.json.JsonObject

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 1 (Structured Tool Results
 * + Outcome Taxonomy Foundation). Optional evidence envelope attached to
 * `ToolResult.Success`/`ToolResult.Failure` (see `Tool.kt`) — NOT a
 * replacement for either, an ADDITIVE field so the tools that don't set it
 * yet keep compiling and behaving exactly as before (`evidence == null`).
 *
 * The principle this type exists to enforce: STATUS != PAYLOAD. A tool that
 * ran successfully and found nothing ([ToolOutcomeStatus.SUCCESS_EMPTY]) is
 * not the same event as a tool that could not run at all
 * ([ToolOutcomeStatus.SOURCE_FAILURE]/[ToolOutcomeStatus.DATA_UNAVAILABLE]),
 * even though both previously collapsed to the same
 * `ToolResult.Failure("..._no_data")` shape — see `GetHealthSummaryTool`'s
 * `weeklySleepResult`/`weeklyBpmResult` for the real bug of this exact shape
 * this phase fixes as its one migrated vertical slice.
 *
 * All fields beyond [status] are nullable and OPTIONAL: a simple local tool
 * (e.g. `get_device_info`, a synchronous Android API read with no concept of
 * staleness, coverage, or a remote source) is never forced to invent values
 * for fields that don't apply to it (§4: "non obbligare ogni tool semplice a
 * riempire metadata non pertinenti"). An explicit `null` is always preferred
 * over a fabricated value the source never actually provided (§6).
 */
data class StructuredToolResult(
    val status: ToolOutcomeStatus,
    /** The tool's real structured output, reusing the SAME [JsonObject] shape [ToolResult] already carries — never re-serialized/re-parsed. Null when there is nothing to show (e.g. [ToolOutcomeStatus.PERMISSION_MISSING]). */
    val payload: JsonObject? = null,
    /** Provider/source identity when distinct from the tool's own name (e.g. `"health_connect"`, `"open_meteo"`). */
    val sourceId: String? = null,
    val recordId: String? = null,
    val revision: String? = null,
    /** What was actually asked for (e.g. `"week"`, `"2026-09-07"`), for comparing against [coverage]. */
    val requestedRange: String? = null,
    /** What the source actually had data for — may be narrower than [requestedRange] (a real coverage gap, distinct from [ToolOutcomeStatus.SOURCE_FAILURE]). */
    val coverage: String? = null,
    /** When the underlying data point was recorded/observed by its source, epoch millis. */
    val observedAt: Long? = null,
    /** When THIS call actually fetched it, epoch millis — may be well after [observedAt] for cached/stale data. */
    val retrievedAt: Long? = null,
    val unit: String? = null,
    /**
     * Whether the capability itself judged this payload stale — a DECISION
     * this envelope only carries, never computes: §8 keeps the fresh/stale
     * policy (TTL, semantics) owned by whichever source/capability actually
     * understands this specific kind of data (weather staleness differs from
     * health staleness differs from agenda staleness).
     */
    val stale: Boolean? = null,
    /** Whether retrying the SAME request is expected to help (true for a timeout/offline gap, false for invalid arguments). */
    val retryable: Boolean? = null,
    /** Machine-readable cause, distinct from any user-facing message — e.g. `"offline"`, `"timeout"`. Never personal data (§16). */
    val reasonCode: String? = null,
    /** Present only for a side-effect call that actually committed something — an opaque id a future Action Layer could use for idempotency/undo. Not a receipt STORE (§13, out of scope this phase) — just a value carried through when the tool already has one. */
    val receiptId: String? = null,
    /** § PARTIAL — reason codes for the sub-parts that did NOT succeed, when [status] is [ToolOutcomeStatus.PARTIAL]; [payload] holds the parts that DID. Null for every other status. */
    val partialFailureReasons: List<String>? = null,
) {
    companion object {
        fun successData(
            payload: JsonObject,
            sourceId: String? = null,
            retrievedAt: Long? = null,
            observedAt: Long? = null,
            coverage: String? = null,
            unit: String? = null,
        ) = StructuredToolResult(
            status = ToolOutcomeStatus.SUCCESS_DATA, payload = payload, sourceId = sourceId,
            retrievedAt = retrievedAt, observedAt = observedAt, coverage = coverage, unit = unit,
        )

        fun successEmpty(
            sourceId: String? = null,
            retrievedAt: Long? = null,
            requestedRange: String? = null,
            coverage: String? = null,
        ) = StructuredToolResult(
            status = ToolOutcomeStatus.SUCCESS_EMPTY, sourceId = sourceId,
            retrievedAt = retrievedAt, requestedRange = requestedRange, coverage = coverage,
        )

        fun dataUnavailable(sourceId: String? = null, reasonCode: String? = null, retryable: Boolean? = null) =
            StructuredToolResult(status = ToolOutcomeStatus.DATA_UNAVAILABLE, sourceId = sourceId, reasonCode = reasonCode, retryable = retryable)

        fun permissionMissing(sourceId: String? = null, reasonCode: String? = null) =
            StructuredToolResult(status = ToolOutcomeStatus.PERMISSION_MISSING, sourceId = sourceId, reasonCode = reasonCode, retryable = false)

        fun stale(payload: JsonObject, sourceId: String? = null, observedAt: Long? = null, retrievedAt: Long? = null) =
            StructuredToolResult(
                status = ToolOutcomeStatus.STALE, payload = payload, sourceId = sourceId,
                observedAt = observedAt, retrievedAt = retrievedAt, stale = true,
            )

        fun sourceFailure(sourceId: String? = null, reasonCode: String? = null, retryable: Boolean? = null) =
            StructuredToolResult(status = ToolOutcomeStatus.SOURCE_FAILURE, sourceId = sourceId, reasonCode = reasonCode, retryable = retryable)

        fun toolFailure(reasonCode: String? = null, retryable: Boolean? = null) =
            StructuredToolResult(status = ToolOutcomeStatus.TOOL_FAILURE, reasonCode = reasonCode, retryable = retryable)

        fun partial(payload: JsonObject, partialFailureReasons: List<String>, sourceId: String? = null) =
            StructuredToolResult(status = ToolOutcomeStatus.PARTIAL, payload = payload, sourceId = sourceId, partialFailureReasons = partialFailureReasons)
    }
}

/**
 * § PASSAGGIO 1 §18 — the ONE place that decides what status a caller should
 * treat a legacy tool outcome as when it carries no [StructuredToolResult]
 * yet. [wasSuccess] is true for a `ToolOutcome.Done`, false for
 * `ToolOutcome.Failed` (kept as a plain `Boolean` here, not `ToolOutcome`
 * itself, so this stays a pure `:core` function usable from any caller
 * shape — the Android-side `ToolOutcome` extension in `app/tools/` is a thin
 * wrapper over this).
 *
 * NEVER infers [ToolOutcomeStatus.SUCCESS_EMPTY] from an ambiguous legacy
 * failure (§18: "rappresentarlo come UNKNOWN/UNAVAILABLE piuttosto che
 * inventare empty") — a legacy success keeps meaning exactly what it always
 * meant (real data), a legacy failure becomes the generic
 * [ToolOutcomeStatus.TOOL_FAILURE], never anything more specific than that
 * honest guess.
 */
fun resolveOutcomeStatus(evidence: StructuredToolResult?, wasSuccess: Boolean): ToolOutcomeStatus =
    evidence?.status ?: if (wasSuccess) ToolOutcomeStatus.SUCCESS_DATA else ToolOutcomeStatus.TOOL_FAILURE
