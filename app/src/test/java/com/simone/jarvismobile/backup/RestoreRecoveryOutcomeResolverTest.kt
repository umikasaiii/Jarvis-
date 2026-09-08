package com.simone.jarvismobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10.2 §1/§9 — the pure
 * decision table behind [RestoreRecoveryOutcome], exercised directly (no
 * Android/Context/Room/DataStore needed — [RestoreRecoveryOutcomeResolver]
 * touches none of them).
 */
class RestoreRecoveryOutcomeResolverTest {

    // --- test 1: no pending recovery -> startup allowed ---------------------

    @Test fun noPendingWorkIsAlwaysNoRecoveryPendingRegardlessOfOtherFlags() {
        assertEquals(
            RestoreRecoveryOutcome.NO_RECOVERY_PENDING,
            RestoreRecoveryOutcomeResolver.resolve(hadPendingWork = false, canonicalCutoverOk = true, cacheClearOk = true),
        )
        // Even if the (impossible in practice, but never trusted blindly)
        // combination claims a failure alongside "nothing was pending," the
        // absence of pending work wins — there is nothing to be incomplete about.
        assertEquals(
            RestoreRecoveryOutcome.NO_RECOVERY_PENDING,
            RestoreRecoveryOutcomeResolver.resolve(hadPendingWork = false, canonicalCutoverOk = false, cacheClearOk = false),
        )
    }

    // --- test 2: successful recovery -> startup allowed ----------------------

    @Test fun pendingWorkFullyResolvedIsRecoveryCompleted() {
        val outcome = RestoreRecoveryOutcomeResolver.resolve(hadPendingWork = true, canonicalCutoverOk = true, cacheClearOk = true)
        assertEquals(RestoreRecoveryOutcome.RECOVERY_COMPLETED, outcome)
        assertTrue(outcome.startupSafe)
    }

    // --- test 3: partial cutover -> RECOVERY_INCOMPLETE -----------------------

    @Test fun failedCanonicalCutoverIsIncompleteRegardlessOfCacheClear() {
        assertEquals(
            RestoreRecoveryOutcome.RECOVERY_INCOMPLETE,
            RestoreRecoveryOutcomeResolver.resolve(hadPendingWork = true, canonicalCutoverOk = false, cacheClearOk = true),
        )
        assertEquals(
            RestoreRecoveryOutcome.RECOVERY_INCOMPLETE,
            RestoreRecoveryOutcomeResolver.resolve(hadPendingWork = true, canonicalCutoverOk = false, cacheClearOk = false),
        )
    }

    // --- test 13: cache-clear failure never silently becomes full success ----

    @Test fun successfulCanonicalCutoverWithFailedCacheClearIsStillIncomplete() {
        val outcome = RestoreRecoveryOutcomeResolver.resolve(hadPendingWork = true, canonicalCutoverOk = true, cacheClearOk = false)
        assertEquals(RestoreRecoveryOutcome.RECOVERY_INCOMPLETE, outcome)
        assertFalse("a restored-but-uncleared derived cache must never look like full success", outcome.startupSafe)
    }

    // --- tests 4/6: startup gating policy -------------------------------------

    @Test fun startupSafeIsTrueOnlyForNoRecoveryPendingAndCompleted() {
        assertTrue(RestoreRecoveryOutcome.NO_RECOVERY_PENDING.startupSafe)
        assertTrue(RestoreRecoveryOutcome.RECOVERY_COMPLETED.startupSafe)
    }

    @Test fun startupSafeIsFalseForIncompleteAndFailed() {
        assertFalse(RestoreRecoveryOutcome.RECOVERY_INCOMPLETE.startupSafe)
        assertFalse(RestoreRecoveryOutcome.RECOVERY_FAILED.startupSafe)
    }

    @Test fun everyOutcomeHasAnExplicitStartupSafeDecision() {
        // No outcome is ever ambiguous — every enum value maps to exactly
        // one of the two buckets, never something the caller has to guess.
        val bucketed = RestoreRecoveryOutcome.entries.groupBy { it.startupSafe }
        assertEquals(setOf(true, false), bucketed.keys)
        assertEquals(2, bucketed[true]?.size)
        assertEquals(2, bucketed[false]?.size)
    }
}
