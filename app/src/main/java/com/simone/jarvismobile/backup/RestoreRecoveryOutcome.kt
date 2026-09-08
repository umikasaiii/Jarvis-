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
 */
enum class RestoreRecoveryOutcome {
    /** No staging leftovers and no pending derived-cache-clear marker existed at all — normal startup. */
    NO_RECOVERY_PENDING,

    /** Every staged entry (if any) cut over, AND the derived-cache clear succeeded or was never needed — normal startup. */
    RECOVERY_COMPLETED,

    /**
     * Canonical cutover left at least one staged entry unresolved, OR the
     * derived-cache clear did not succeed this pass. Restored-state
     * consumers (schedulers/repositories that could read the just-restored
     * Room DB, DataStore, or its derived caches) MUST NOT start — staging
     * and every marker are left exactly as they were so a later cold start
     * can retry.
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
 */
internal object RestoreRecoveryOutcomeResolver {
    fun resolve(hadPendingWork: Boolean, canonicalCutoverOk: Boolean, cacheClearOk: Boolean): RestoreRecoveryOutcome = when {
        !hadPendingWork -> RestoreRecoveryOutcome.NO_RECOVERY_PENDING
        !canonicalCutoverOk -> RestoreRecoveryOutcome.RECOVERY_INCOMPLETE
        !cacheClearOk -> RestoreRecoveryOutcome.RECOVERY_INCOMPLETE
        else -> RestoreRecoveryOutcome.RECOVERY_COMPLETED
    }
}
