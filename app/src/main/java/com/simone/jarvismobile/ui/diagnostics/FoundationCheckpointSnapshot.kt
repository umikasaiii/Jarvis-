package com.simone.jarvismobile.ui.diagnostics

import com.simone.jarvismobile.automation.rule.AutomationOccurrenceDiagnostic
import com.simone.jarvismobile.backup.RestoreDiagnostic
import com.simone.jarvismobile.core.engine.EngineTurnDiagnostics
import com.simone.jarvismobile.navigation.NavigationDiagnostic

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 11 §G — a small, READ-ONLY
 * composition of evidence already produced by the stabilized PASSAGGIO
 * 1–10.3 domains, not a second state machine and not a new source of
 * truth: every field here is a direct reference to a `StateFlow` that
 * already existed (or, for [automation], was added in this same
 * checkpoint as the smallest possible extension — see
 * `AutomationExecutor.lastOccurrence`). Nothing here is recomputed or
 * reinterpreted; [DiagnosticsViewModel.foundationCheckpoint] just combines
 * them into one snapshot so a checkpoint can be read at a glance.
 *
 * Deliberately excludes: health values, exact location, route geometry,
 * message/memory contents, secrets, full tool payloads — every field below
 * is either an identity/status enum, a count, or a boolean.
 */
data class FoundationCheckpointSnapshot(
    /** `BuildConfig.BUILD_ID` — the short git SHA this build was compiled from, or "" outside CI. */
    val buildId: String,
    /** Most recent conversational turn's structured telemetry (routing path, grounding, tool outcome statuses) — null before the first turn. */
    val lastEngineTurn: EngineTurnDiagnostics?,
    /** Most recent navigation route/reroute computation's generation/outcome — null before the first computation this process. */
    val navigation: NavigationDiagnostic?,
    /** Most recent restore attempt's stage-by-stage evidence — null if no restore has been attempted this process. */
    val restore: RestoreDiagnostic?,
    /** Most recent automation trigger's occurrence decision — null before the first trigger this process. */
    val automation: AutomationOccurrenceDiagnostic?,
    /** Whether Core currently looks reachable, per the same `CoreConnectionManager` state the Settings screen shows. */
    val coreAvailable: Boolean,
)
