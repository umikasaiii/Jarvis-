package com.simone.jarvismobile.core.proactive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.3 §4/§5. Pure tests
 * for the last defensive bound applied to persistent trigger diagnostics —
 * never briefing/agenda/health/weather content, always a short bounded
 * fragment.
 */
class TriggerEvidenceTest {

    @Test
    fun `null detail stays null`() {
        assertNull(TriggerEvidencePolicy.sanitizeDetail(null))
    }

    @Test
    fun `blank detail becomes null rather than an empty string`() {
        assertNull(TriggerEvidencePolicy.sanitizeDetail("   "))
    }

    @Test
    fun `a short whitelisted fragment passes through unchanged`() {
        assertEquals("offsetMinutes=5", TriggerEvidencePolicy.sanitizeDetail("offsetMinutes=5"))
    }

    @Test
    fun `newlines and carriage returns are flattened to spaces`() {
        val sanitized = TriggerEvidencePolicy.sanitizeDetail("line1\nline2\r\nline3")
        assertTrue(!sanitized!!.contains('\n') && !sanitized.contains('\r'))
    }

    @Test
    fun `detail longer than the bound is truncated, never dropped entirely`() {
        val long = "x".repeat(TriggerEvidencePolicy.MAX_DETAIL_CHARS + 50)
        val sanitized = TriggerEvidencePolicy.sanitizeDetail(long)
        assertEquals(TriggerEvidencePolicy.MAX_DETAIL_CHARS, sanitized!!.length)
    }

    @Test
    fun `detail at exactly the bound is left intact`() {
        val exact = "y".repeat(TriggerEvidencePolicy.MAX_DETAIL_CHARS)
        assertEquals(exact, TriggerEvidencePolicy.sanitizeDetail(exact))
    }

    @Test
    fun `leading and trailing whitespace is trimmed before bounding`() {
        assertEquals("a=1", TriggerEvidencePolicy.sanitizeDetail("   a=1   "))
    }
}
