package com.simone.jarvismobile.core.guardian

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardianPolicyTest {
    private val today = LocalDate.of(2026, 11, 11)

    @Test fun lockScreenNeverInventsSecurity() {
        assertEquals(GuardianState.PROTECTED, GuardianPolicy.lockScreen(true).state)
        assertEquals(GuardianState.ATTENTION, GuardianPolicy.lockScreen(false).state)
        assertEquals(GuardianState.UNVERIFIED, GuardianPolicy.lockScreen(null).state)
    }

    @Test fun patchChecksCalendarDaysAndMissingEvidence() {
        assertEquals(GuardianState.PROTECTED, GuardianPolicy.securityPatch("2026-11-01", today).state)
        assertEquals(GuardianState.ATTENTION, GuardianPolicy.securityPatch("2026-01-01", today).state)
        assertEquals(GuardianState.PROTECTED, GuardianPolicy.securityPatch("2026-08-13", today).state) // 90 days exactly
        assertEquals(GuardianState.ATTENTION, GuardianPolicy.securityPatch("2026-08-12", today).state)
        assertEquals(GuardianState.UNVERIFIED, GuardianPolicy.securityPatch("no-date", today).state)
        assertEquals(GuardianState.UNVERIFIED, GuardianPolicy.securityPatch("", today).state)
        assertEquals(GuardianState.UNVERIFIED, GuardianPolicy.securityPatch("2027-01-01", today).state)
    }

    @Test fun advancedProtectionIsNeverAssumedEnabled() {
        assertEquals(GuardianState.UNAVAILABLE, GuardianPolicy.advancedProtection(false, false, null).state)
        assertEquals(GuardianState.UNVERIFIED, GuardianPolicy.advancedProtection(true, false, true).state)
        assertEquals(GuardianState.UNVERIFIED, GuardianPolicy.advancedProtection(true, true, null).state)
        assertEquals(GuardianState.ATTENTION, GuardianPolicy.advancedProtection(true, true, false).state)
        assertEquals(GuardianState.PROTECTED, GuardianPolicy.advancedProtection(true, true, true).state)
    }

    @Test fun debugBuildIsNeverCalledReleaseProtected() {
        assertEquals(GuardianState.ATTENTION, GuardianPolicy.betaSigning(true).state)
        assertEquals(GuardianState.UNVERIFIED, GuardianPolicy.betaSigning(false).state)
    }

    @Test fun fileSizeIsStrictlyBounded() {
        assertTrue(GuardianPolicy.fileSizeAllowed(0))
        assertTrue(GuardianPolicy.fileSizeAllowed(GuardianPolicy.MAX_FILE_BYTES))
        assertFalse(GuardianPolicy.fileSizeAllowed(-1))
        assertFalse(GuardianPolicy.fileSizeAllowed(GuardianPolicy.MAX_FILE_BYTES + 1))
    }
}
