package com.simone.jarvismobile.backup

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10.2 §1 — the explicit result
 * of [BackupRepository.completePendingRestoreRecovery], replacing the bare
 * `Unit` it used to return. A `Unit` return hid three genuinely different
 * situations behind one shape: "nothing was pending," "recovery actually
 * finished," and "recovery is still incomplete" — the exact gap PASSAGGIO
 * 10.1's startup barrier left open, since `JarvisApplication` could `await`
 * the call, ignore whatever came back, and launch every restored-state
 * consumer regardless.
 *
 * § PASSAGGIO 10.3 §1/§2/§3 — "recovery finished" was itself too coarse: a
 * successful file cutover alone is not enough. [RECOVERY_COMPLETED] now
 * additionally requires that the post-cutover markers (sanitize/derived-
 * cache) were durably persisted AND that restored `assistant_tasks` rows
 * were actually proven sanitized (Room opened, its migrations ran,
 * `RestoreSanitizeCallback.onOpen()` cleared the marker) before this cold
 * start's barrier releases — never just "the marker got written."
 */
enum class RestoreRecoveryOutcome {
    /** No staging leftovers and no pending sanitize/derived-cache marker existed at all — normal startup. */
    NO_RECOVERY_PENDING,

    /**
     * Every staged entry (if any) cut over, every required marker
     * (sanitize/derived-cache) was durably persisted, restored
     * `assistant_tasks` sanitization was proven complete (marker cleared),
     * and the derived-cache clear succeeded or was never needed — normal
     * startup.
     */
    RECOVERY_COMPLETED,

    /**
     * At least one of the following happened this pass: a staged entry
     * failed to cut over; a required marker failed to persist durably;
     * `assistant_tasks` sanitization could not be proven complete; or the
     * derived-cache clear did not succeed. Restored-state consumers
     * (schedulers/repositories that could read the just-restored Room DB,
     * DataStore, or its derived caches) MUST NOT start — staging and every
     * marker are left exactly as they were so a later cold start can retry.
     */
    RECOVERY_INCOMPLETE,

    /**
     * [BackupRepository.completePendingRestoreRecovery] itself threw
     * (never [kotlinx.coroutines.CancellationException], which always
     * propagates unmodified instead of becoming this value) — treated
     * identically to [RECOVERY_INCOMPLETE] for startup gating: the caller
     * could not even determine whether recovery finished, so it must
     * assume the worst.
     */
    RECOVERY_FAILED,
    ;

    /** True only for the two outcomes it is safe to start restored-state-consuming schedulers/repositories behind. */
    val startupSafe: Boolean get() = this == NO_RECOVERY_PENDING || this == RECOVERY_COMPLETED
}

/**
 * Pure decision table behind [RestoreRecoveryOutcome] — no Android, no File
 * I/O — so the actual branching logic is unit-testable on a plain JVM,
 * independent of [BackupRepository]'s real `Context`/Room/DataStore
 * dependencies (which this project does not exercise via Robolectric).
 *
 * § PASSAGGIO 10.3 — [sanitizeMarkerPersistedOk]/[cacheMarkerPersistedOk]
 * and [sanitizeOk] are new, separate dimensions from [filesCutoverOk]/
 * [cacheClearOk]: a marker can fail to persist even when the file cutover
 * itself succeeded (§2), and `assistant_tasks` sanitization is a distinct
 * step — proven only by Room actually clearing the marker, not by the
 * marker merely having been written (§3/§4).
 */
internal object RestoreRecoveryOutcomeResolver {
    fun resolve(
        hadPendingWork: Boolean,
        filesCutoverOk: Boolean,
        sanitizeMarkerPersistedOk: Boolean,
        cacheMarkerPersistedOk: Boolean,
        sanitizeOk: Boolean,
        cacheClearOk: Boolean,
    ): RestoreRecoveryOutcome = when {
        !hadPendingWork -> RestoreRecoveryOutcome.NO_RECOVERY_PENDING
        !filesCutoverOk -> RestoreRecoveryOutcome.RECOVERY_INCOMPLETE
        !sanitizeMarkerPersistedOk -> RestoreRecoveryOutcome.RECOVERY_INCOMPLETE
        !cacheMarkerPersistedOk -> RestoreRecoveryOutcome.RECOVERY_INCOMPLETE
        !sanitizeOk -> RestoreRecoveryOutcome.RECOVERY_INCOMPLETE
        !cacheClearOk -> RestoreRecoveryOutcome.RECOVERY_INCOMPLETE
        else -> RestoreRecoveryOutcome.RECOVERY_COMPLETED
    }
}
