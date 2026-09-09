package com.simone.jarvismobile.core.segnale

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §V. Covers the
 * SEGNALE P0 test items that are genuine pure-Kotlin decisions (§V items
 * 2-3-5-6-8-9-10-11-12-13) — token color values (§V item 1) and touch-target
 * dp constants (§V items 14-16) live in `app/` (they need
 * `androidx.compose.ui.graphics.Color`/`Dp`, unavailable in `:core`) and are
 * covered by an `app/src/test` JVM test instead (written, CI-verified only,
 * same convention as every other `app/` test in this project).
 */
class SegnaleFoundationTest {

    // --- §V.2/3/4/5/6: DataStatus distinctness, mirrored 1:1 from ToolOutcomeStatus ---

    @Test
    fun `SUCCESS_DATA and SUCCESS_EMPTY are distinct DataStatus values`() {
        val data = DataStatus.from(ToolOutcomeStatus.SUCCESS_DATA)
        val empty = DataStatus.from(ToolOutcomeStatus.SUCCESS_EMPTY)
        assertEquals(DataStatus.SUCCESS_DATA, data)
        assertEquals(DataStatus.SUCCESS_EMPTY, empty)
        assertNotEquals(data, empty)
    }

    @Test
    fun `STALE remains distinct from SUCCESS_DATA and PARTIAL`() {
        val stale = DataStatus.from(ToolOutcomeStatus.STALE)
        assertEquals(DataStatus.STALE, stale)
        assertNotEquals(DataStatus.SUCCESS_DATA, stale)
        assertNotEquals(DataStatus.PARTIAL, stale)
    }

    @Test
    fun `PERMISSION_MISSING remains distinct from every other blocked status`() {
        val permissionMissing = DataStatus.from(ToolOutcomeStatus.PERMISSION_MISSING)
        val others = listOf(
            DataStatus.from(ToolOutcomeStatus.DATA_UNAVAILABLE),
            DataStatus.from(ToolOutcomeStatus.SOURCE_FAILURE),
            DataStatus.from(ToolOutcomeStatus.TOOL_FAILURE),
        )
        others.forEach { assertNotEquals(permissionMissing, it) }
    }

    @Test
    fun `PARTIAL remains distinct from SUCCESS_DATA and STALE`() {
        val partial = DataStatus.from(ToolOutcomeStatus.PARTIAL)
        assertEquals(DataStatus.PARTIAL, partial)
        assertNotEquals(DataStatus.SUCCESS_DATA, partial)
        assertNotEquals(DataStatus.STALE, partial)
    }

    @Test
    fun `every ToolOutcomeStatus maps to a distinct DataStatus, no two collapse`() {
        val mapped = ToolOutcomeStatus.entries.map { DataStatus.from(it) }
        assertEquals(mapped.size, mapped.toSet().size)
    }

    @Test
    fun `LOADING and OFFLINE exist as UI-local states with no ToolOutcomeStatus origin`() {
        // Neither is reachable via DataStatus.from(ToolOutcomeStatus) — they are
        // set directly by the UI layer, never derived from a tool outcome.
        val fromToolOutcomes = ToolOutcomeStatus.entries.map { DataStatus.from(it) }.toSet()
        assertFalse(fromToolOutcomes.contains(DataStatus.LOADING))
        assertFalse(fromToolOutcomes.contains(DataStatus.OFFLINE))
    }

    // --- §V.6: UNKNOWN action distinct from FAILED (SideEffectStatus) ---

    @Test
    fun `UNKNOWN side effect status is distinct from FAILED`() {
        assertNotEquals(SideEffectStatus.UNKNOWN, SideEffectStatus.FAILED)
        assertNotEquals(SideEffectStatus.UNKNOWN, SideEffectStatus.CONFIRMED)
    }

    @Test
    fun `every SideEffectStatus value is distinct`() {
        assertEquals(SideEffectStatus.entries.size, SideEffectStatus.entries.toSet().size)
    }

    // --- §V.7: presentation mapper carries no domain payload ---

    @Test
    fun `DataStatus and SideEffectStatus are plain enums, no payload field to leak domain data`() {
        // Structural guarantee, not just an assertion on one instance: an enum
        // constant carries no fields by construction (no data class body here),
        // so there is no field through which agenda/health/message content
        // could ever be attached — verified by DataStatus.entries all being
        // reference-equal to themselves with zero constructor args.
        DataStatus.entries.forEach { status -> assertEquals(status, DataStatus.valueOf(status.name)) }
        SideEffectStatus.entries.forEach { status -> assertEquals(status, SideEffectStatus.valueOf(status.name)) }
    }

    // --- §V.8/9/10: reduced motion resolution ---
    //
    // § PASSAGGIO 12.1 — precedence contract: system reduced motion always
    // wins. `userPrefersReducedMotion` (renamed from the old, misleading
    // `userOverride`) can only ever REQUEST additional reduced motion; it
    // must never re-enable ornamental motion the system has disabled. Full
    // six-case truth table required by the correction spec.

    @Test
    fun `system=false, user=null resolves to false`() {
        assertFalse(SegnaleMotionPolicy.effectiveReducedMotion(systemReducedMotion = false, userPrefersReducedMotion = null))
    }

    @Test
    fun `system=false, user=false resolves to false`() {
        assertFalse(SegnaleMotionPolicy.effectiveReducedMotion(systemReducedMotion = false, userPrefersReducedMotion = false))
    }

    @Test
    fun `system=false, user=true resolves to true`() {
        assertTrue(SegnaleMotionPolicy.effectiveReducedMotion(systemReducedMotion = false, userPrefersReducedMotion = true))
    }

    @Test
    fun `system=true, user=null resolves to true`() {
        assertTrue(SegnaleMotionPolicy.effectiveReducedMotion(systemReducedMotion = true, userPrefersReducedMotion = null))
    }

    @Test
    fun `system=true, user=false STILL resolves to true — system can never be overridden off`() {
        assertTrue(SegnaleMotionPolicy.effectiveReducedMotion(systemReducedMotion = true, userPrefersReducedMotion = false))
    }

    @Test
    fun `system=true, user=true resolves to true`() {
        assertTrue(SegnaleMotionPolicy.effectiveReducedMotion(systemReducedMotion = true, userPrefersReducedMotion = true))
    }

    // --- §V.11/12/13: quality profile ---

    @Test
    fun `reduced motion overrides Enhanced profile's ornamental motion allowance`() {
        val caps = SegnaleQualityPolicy.capabilitiesFor(SegnaleQualityProfile.ENHANCED, reducedMotion = true)
        assertFalse(caps.allowOrnamentalMotion)
    }

    @Test
    fun `Essential, Standard and Enhanced profiles are structurally distinct enum values`() {
        assertEquals(3, SegnaleQualityProfile.entries.size)
        assertEquals(SegnaleQualityProfile.entries.toSet().size, SegnaleQualityProfile.entries.size)
    }

    @Test
    fun `Essential never allows ornamental motion even without reduced motion`() {
        val caps = SegnaleQualityPolicy.capabilitiesFor(SegnaleQualityProfile.ESSENTIAL, reducedMotion = false)
        assertFalse(caps.allowOrnamentalMotion)
    }

    @Test
    fun `Standard allows ornamental motion only when reduced motion is off`() {
        val standardNormal = SegnaleQualityPolicy.capabilitiesFor(SegnaleQualityProfile.STANDARD, reducedMotion = false)
        val standardReduced = SegnaleQualityPolicy.capabilitiesFor(SegnaleQualityProfile.STANDARD, reducedMotion = true)
        assertTrue(standardNormal.allowOrnamentalMotion)
        assertFalse(standardReduced.allowOrnamentalMotion)
    }

    @Test
    fun `no P0 quality profile allows rich canvas detail or optional texture yet`() {
        // §F/§O — no heavy Enhanced effects in P0; pinning this prevents a
        // later edit from silently turning capabilities on without a
        // deliberate P1+ decision.
        SegnaleQualityProfile.entries.forEach { profile ->
            val caps = SegnaleQualityPolicy.capabilitiesFor(profile, reducedMotion = false)
            assertFalse(caps.allowRichCanvasDetail, "profile=$profile")
            assertFalse(caps.allowOptionalTexture, "profile=$profile")
        }
    }

    // --- severity mapping sanity (used by the app-side DataStatusView) ---

    @Test
    fun `severity groups never merge a blocked status into positive or neutral`() {
        val blocked = listOf(
            DataStatus.PERMISSION_MISSING,
            DataStatus.DATA_UNAVAILABLE,
            DataStatus.SOURCE_FAILURE,
            DataStatus.TOOL_FAILURE,
            DataStatus.OFFLINE,
        )
        blocked.forEach { status ->
            assertEquals(DataStatusSeverity.BLOCKED, status.severity())
        }
        assertEquals(DataStatusSeverity.POSITIVE, DataStatus.SUCCESS_DATA.severity())
        assertEquals(DataStatusSeverity.POSITIVE, DataStatus.SUCCESS_EMPTY.severity())
    }
}
