package com.simone.jarvismobile.core.segnale

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §I (SEGNALE P0,
 * Runtime Presentation / Data Status). The presentation-level projection of
 * a real runtime read outcome — never a second source of truth: every
 * value here is derived from an already-real [ToolOutcomeStatus] (§ PASSAGGIO
 * 1) or a UI-local transport concern ([LOADING]/[OFFLINE], which
 * [ToolOutcomeStatus] does not model because they are not tool outcomes at
 * all — a read that has not returned yet, or a device with no network).
 *
 * A Composable must never infer this from a string/keyword — always from a
 * typed producer already in the domain (see `from`/callers in `app/`).
 */
enum class DataStatus {
    /** Real data, successfully read. Mirrors [ToolOutcomeStatus.SUCCESS_DATA]. */
    SUCCESS_DATA,

    /** A genuine, confirmed "nothing there". Mirrors [ToolOutcomeStatus.SUCCESS_EMPTY] — never produced from an ambiguous failure. */
    SUCCESS_EMPTY,

    /** The read is in flight — a UI-local transport state, not a tool outcome. */
    LOADING,

    /** Real data, but known-old. Mirrors [ToolOutcomeStatus.STALE]. */
    STALE,

    /** The user has not granted (or has revoked) the permission this data requires. Mirrors [ToolOutcomeStatus.PERMISSION_MISSING]. */
    PERMISSION_MISSING,

    /** The source is reachable but does not have the requested data — a coverage gap. Mirrors [ToolOutcomeStatus.DATA_UNAVAILABLE]. */
    DATA_UNAVAILABLE,

    /** The upstream source itself failed. Mirrors [ToolOutcomeStatus.SOURCE_FAILURE]. */
    SOURCE_FAILURE,

    /** The tool/capability itself failed. Mirrors [ToolOutcomeStatus.TOOL_FAILURE]. */
    TOOL_FAILURE,

    /** Some sub-parts succeeded, some did not. Mirrors [ToolOutcomeStatus.PARTIAL]. */
    PARTIAL,

    /** The device has no network and the data genuinely requires it — a UI-local connectivity concern, not a tool outcome. */
    OFFLINE,
    ;

    companion object {
        /**
         * The only legal way to obtain a [DataStatus] from a real tool
         * outcome — a 1:1 mirror, never a guess. [LOADING]/[OFFLINE] are
         * set directly by the UI layer from its own connectivity/in-flight
         * state, never derived from a [ToolOutcomeStatus] (there is none
         * yet, or none ever will be, for those two cases).
         */
        fun from(outcome: ToolOutcomeStatus): DataStatus = when (outcome) {
            ToolOutcomeStatus.SUCCESS_DATA -> SUCCESS_DATA
            ToolOutcomeStatus.SUCCESS_EMPTY -> SUCCESS_EMPTY
            ToolOutcomeStatus.STALE -> STALE
            ToolOutcomeStatus.PERMISSION_MISSING -> PERMISSION_MISSING
            ToolOutcomeStatus.DATA_UNAVAILABLE -> DATA_UNAVAILABLE
            ToolOutcomeStatus.SOURCE_FAILURE -> SOURCE_FAILURE
            ToolOutcomeStatus.TOOL_FAILURE -> TOOL_FAILURE
            ToolOutcomeStatus.PARTIAL -> PARTIAL
        }
    }
}

/**
 * Coarse semantic bucket for a [DataStatus] — which SEGNALE accent token
 * (§ PASSAGGIO 12 §D: success/warning/error/remote/neutral) a presentation
 * layer should reach for, never a replacement for reading the real status.
 */
enum class DataStatusSeverity { POSITIVE, NEUTRAL, DEGRADED, BLOCKED }

fun DataStatus.severity(): DataStatusSeverity = when (this) {
    DataStatus.SUCCESS_DATA, DataStatus.SUCCESS_EMPTY -> DataStatusSeverity.POSITIVE
    DataStatus.LOADING -> DataStatusSeverity.NEUTRAL
    DataStatus.STALE, DataStatus.PARTIAL -> DataStatusSeverity.DEGRADED
    DataStatus.PERMISSION_MISSING, DataStatus.DATA_UNAVAILABLE,
    DataStatus.SOURCE_FAILURE, DataStatus.TOOL_FAILURE, DataStatus.OFFLINE,
    -> DataStatusSeverity.BLOCKED
}
