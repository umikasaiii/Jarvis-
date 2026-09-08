package com.simone.jarvismobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10.2 §1/§9, extended by
 * PASSAGGIO 10.3 §1/§2/§3 — the pure decision table behind
 * [RestoreRecoveryOutcome], exercised directly (no Android/Context/Room/
 * DataStore needed — [RestoreRecoveryOutcomeResolver] touches none of them).
 * PASSAGGIO 10.3 adds two new gating dimensions this file's tests were
 * rewritten to cover: marker PERSISTENCE (separate from file cutover) and
 * proven `assistant_tasks` sanitization (separate from marker persistence).
 */
class RestoreRecoveryOutcomeResolverTest {

    private fun allOk(
        hadPendingWork: Boolean = true,
        filesCutoverOk: Boolean = true,
        sanitizeMarkerPersistedOk: Boolean = true,
        cacheMarkerPersistedOk: Boolean = true,
        sanitizeOk: Boolean = true,
        cacheClearOk: Boolean = true,
    ) = RestoreRecoveryOutcomeResolver.resolve(
        hadPendingWork, filesCutoverOk, sanitizeMarkerPersistedOk, cacheMarkerPersistedOk, sanitizeOk, cacheClearOk,
    )

    // --- test 1 / 14: no pending recovery -> startup allowed -----------------

    @Test fun noPendingWorkIsAlwaysNoRecoveryPendingRegardlessOfOtherFlags() {
        assertEquals(RestoreRecoveryOutcome.NO_RECOVERY_PENDING, allOk(hadPendingWork = false))
        // Even if every other flag claims a failure, the absence of pending
        // work wins — there is nothing to be incomplete about.
        assertEquals(
            RestoreRecoveryOutcome.NO_RECOVERY_PENDING,
            allOk(
                hadPendingWork = false, filesCutoverOk = false, sanitizeMarkerPersistedOk = false,
                cacheMarkerPersistedOk = false, sanitizeOk = false, cacheClearOk = false,
            ),
        )
    }

    // --- test 10: successful recovery -> startup allowed ----------------------

    @Test fun everyPrerequisiteSatisfiedIsRecoveryCompleted() {
        val outcome = allOk()
        assertEquals(RestoreRecoveryOutcome.RECOVERY_COMPLETED, outcome)
        assertTrue(outcome.startupSafe)
    }

    // --- files cutover failure -------------------------------------------------

    @Test fun failedFileCutoverIsIncompleteRegardlessOfEverythingElse() {
        assertEquals(RestoreRecoveryOutcome.RECOVERY_INCOMPLETE, allOk(filesCutoverOk = false))
    }

    // --- test 2: sanitize-marker creation failure ⇒ not startupSafe -----------

    @Test fun sanitizeMarkerPersistenceFailureBlocksCompletionEvenWithSuccessfulCutover() {
        val outcome = allOk(sanitizeMarkerPersistedOk = false)
        assertEquals(RestoreRecoveryOutcome.RECOVERY_INCOMPLETE, outcome)
        assertFalse("a db cutover whose sanitize marker failed to persist must never look like full success", outcome.startupSafe)
    }

    // --- test 3: cache-marker creation failure ⇒ not startupSafe --------------

    @Test fun cacheMarkerPersistenceFailureBlocksCompletionEvenWithSuccessfulCutover() {
        val outcome = allOk(cacheMarkerPersistedOk = false)
        assertEquals(RestoreRecoveryOutcome.RECOVERY_INCOMPLETE, outcome)
        assertFalse("a datastore cutover whose cache-clear marker failed to persist must never look like full success", outcome.startupSafe)
    }

    // --- test 7/8: sanitize failure is visible to the barrier -----------------

    @Test fun unprovenSanitizationBlocksCompletionEvenWhenMarkersPersistedAndFilesCutOver() {
        val outcome = allOk(sanitizeOk = false)
        assertEquals(RestoreRecoveryOutcome.RECOVERY_INCOMPLETE, outcome)
        assertFalse("a Room open that did not actually clear the sanitize marker must never count as recovered", outcome.startupSafe)
    }

    // --- test 13: cache clear failure never silently becomes full success ----

    @Test fun failedCacheClearIsStillIncomplete() {
        val outcome = allOk(cacheClearOk = false)
        assertEquals(RestoreRecoveryOutcome.RECOVERY_INCOMPLETE, outcome)
        assertFalse(outcome.startupSafe)
    }

    // --- startup gating policy (unchanged since PASSAGGIO 10.2) --------------

    @Test fun startupSafeIsTrueOnlyForNoRecoveryPendingAndCompleted() {
        assertTrue(RestoreRecoveryOutcome.NO_RECOVERY_PENDING.startupSafe)
        assertTrue(RestoreRecoveryOutcome.RECOVERY_COMPLETED.startupSafe)
    }

    @Test fun startupSafeIsFalseForIncompleteAndFailed() {
        assertFalse(RestoreRecoveryOutcome.RECOVERY_INCOMPLETE.startupSafe)
        assertFalse(RestoreRecoveryOutcome.RECOVERY_FAILED.startupSafe)
    }

    @Test fun everyOutcomeHasAnExplicitStartupSafeDecision() {
        val bucketed = RestoreRecoveryOutcome.entries.groupBy { it.startupSafe }
        assertEquals(setOf(true, false), bucketed.keys)
        assertEquals(2, bucketed[true]?.size)
        assertEquals(2, bucketed[false]?.size)
    }

    // --- multiple simultaneous failures never cancel each other out ----------

    @Test fun multipleFailuresStillResolveToIncomplete() {
        val outcome = allOk(sanitizeMarkerPersistedOk = false, sanitizeOk = false, cacheClearOk = false)
        assertEquals(RestoreRecoveryOutcome.RECOVERY_INCOMPLETE, outcome)
    }
}
