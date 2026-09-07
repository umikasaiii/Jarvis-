package com.simone.jarvismobile.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 1 (Structured Tool Results
 * + Outcome Taxonomy Foundation). Covers the mandatory test list (§19) that
 * is expressible as pure `:core` logic — the Android-side wiring
 * (`ToolRunner`'s cancellation fix, `GetHealthSummaryTool`'s migrated empty-
 * vs-failure fix, `ConversationalJarvisEngine`'s diagnostics threading) is
 * not unit-testable in this environment (no Android SDK, see `CLAUDE.md`)
 * and was instead verified by reading the real source + a programmatic
 * bracket-balance check, per this project's established convention.
 */
class StructuredToolResultTest {

    private val samplePayload = JsonObject(mapOf("value" to JsonPrimitive("42")))

    // § test 1 — SUCCESS_DATA conserva payload e metadata.
    @Test
    fun `successData keeps its payload and provenance metadata`() {
        val result = StructuredToolResult.successData(
            payload = samplePayload,
            sourceId = "open_meteo",
            retrievedAt = 1000L,
            observedAt = 900L,
            coverage = "today",
            unit = "celsius",
        )
        assertEquals(ToolOutcomeStatus.SUCCESS_DATA, result.status)
        assertEquals("42", result.payload?.get("value")?.jsonPrimitive?.content)
        assertEquals("open_meteo", result.sourceId)
        assertEquals(1000L, result.retrievedAt)
        assertEquals(900L, result.observedAt)
        assertEquals("today", result.coverage)
        assertEquals("celsius", result.unit)
    }

    // § test 2 — SUCCESS_EMPTY è distinto da null/error.
    @Test
    fun `successEmpty is a real distinct status, never null and never a failure tier`() {
        val result = StructuredToolResult.successEmpty(sourceId = "health_connect", requestedRange = "week")
        assertEquals(ToolOutcomeStatus.SUCCESS_EMPTY, result.status)
        assertEquals(ToolOutcomeTier.NORMAL, result.status.tier())
        assertNotEquals(ToolOutcomeStatus.DATA_UNAVAILABLE, result.status)
        assertNotEquals(ToolOutcomeStatus.TOOL_FAILURE, result.status)
        // an empty result legitimately carries no payload — that is not the same as "unknown/failed"
        assertNull(result.payload)
        assertEquals("week", result.requestedRange)
    }

    // § test 3 — SOURCE_FAILURE non diventa empty.
    @Test
    fun `sourceFailure is never mistaken for successEmpty`() {
        val failure = StructuredToolResult.sourceFailure(sourceId = "open_meteo", reasonCode = "network_error")
        assertEquals(ToolOutcomeStatus.SOURCE_FAILURE, failure.status)
        assertEquals(ToolOutcomeTier.UNAVAILABLE, failure.status.tier())
        assertNotEquals(ToolOutcomeStatus.SUCCESS_EMPTY, failure.status)
        assertNotEquals(ToolOutcomeTier.NORMAL, failure.status.tier())
    }

    // § test 4 — PERMISSION_MISSING resta distinto.
    @Test
    fun `permissionMissing is distinct from every other unavailable status`() {
        val permission = StructuredToolResult.permissionMissing(sourceId = "health_connect", reasonCode = "resting_heart_rate")
        val source = StructuredToolResult.sourceFailure(sourceId = "health_connect")
        val unavailable = StructuredToolResult.dataUnavailable(sourceId = "health_connect")
        assertEquals(ToolOutcomeStatus.PERMISSION_MISSING, permission.status)
        assertNotEquals(permission.status, source.status)
        assertNotEquals(permission.status, unavailable.status)
        assertEquals(false, permission.retryable) // granting permission is a user action, not a mechanical retry
    }

    // § test 5 — STALE conserva payload + freshness.
    @Test
    fun `stale preserves the real payload alongside its observed and retrieved timestamps`() {
        val result = StructuredToolResult.stale(payload = samplePayload, sourceId = "open_meteo", observedAt = 500L, retrievedAt = 2000L)
        assertEquals(ToolOutcomeStatus.STALE, result.status)
        assertEquals(ToolOutcomeTier.DEGRADED, result.status.tier())
        assertEquals("42", result.payload?.get("value")?.jsonPrimitive?.content)
        assertEquals(500L, result.observedAt)
        assertEquals(2000L, result.retrievedAt)
        assertEquals(true, result.stale)
    }

    // § test 6 — PARTIAL conserva successi e failure.
    @Test
    fun `partial keeps both the succeeded payload and the reasons for what failed`() {
        val result = StructuredToolResult.partial(
            payload = samplePayload,
            partialFailureReasons = listOf("weather_unavailable"),
            sourceId = "multi_source",
        )
        assertEquals(ToolOutcomeStatus.PARTIAL, result.status)
        assertEquals(ToolOutcomeTier.DEGRADED, result.status.tier())
        assertEquals("42", result.payload?.get("value")?.jsonPrimitive?.content)
        assertEquals(listOf("weather_unavailable"), result.partialFailureReasons)
    }

    // § test 11 — legacy adapter ambiguo non inventa SUCCESS_EMPTY.
    @Test
    fun `resolveOutcomeStatus never invents SUCCESS_EMPTY from an ambiguous legacy outcome`() {
        val legacySuccess = resolveOutcomeStatus(evidence = null, wasSuccess = true)
        val legacyFailure = resolveOutcomeStatus(evidence = null, wasSuccess = false)
        assertEquals(ToolOutcomeStatus.SUCCESS_DATA, legacySuccess) // unchanged meaning: a legacy Done always meant real data
        assertEquals(ToolOutcomeStatus.TOOL_FAILURE, legacyFailure) // the honest generic guess, never SUCCESS_EMPTY
        assertNotEquals(ToolOutcomeStatus.SUCCESS_EMPTY, legacyFailure)
    }

    @Test
    fun `resolveOutcomeStatus defers to real evidence when a tool has migrated`() {
        val evidence = StructuredToolResult.successEmpty(sourceId = "health_connect")
        // even though the outcome was technically "successful" at the ToolOutcome.Done level,
        // the real evidence status (not the wasSuccess guess) must win.
        assertEquals(ToolOutcomeStatus.SUCCESS_EMPTY, resolveOutcomeStatus(evidence, wasSuccess = true))
    }

    // § test 12 — backward compatibility dei tool già funzionanti coinvolti.
    @Test
    fun `a tool built before this phase still compiles and defaults to no evidence`() {
        val legacySuccess = ToolResult.Success(samplePayload)
        val legacyFailure = ToolResult.Failure("some_code")
        assertNull(legacySuccess.evidence)
        assertNull(legacyFailure.evidence)
    }

    @Test
    fun `every unavailable-tier status actually blocks a naive success check`() {
        val unavailableStatuses = listOf(
            ToolOutcomeStatus.DATA_UNAVAILABLE,
            ToolOutcomeStatus.PERMISSION_MISSING,
            ToolOutcomeStatus.SOURCE_FAILURE,
            ToolOutcomeStatus.TOOL_FAILURE,
        )
        unavailableStatuses.forEach { status ->
            assertEquals(ToolOutcomeTier.UNAVAILABLE, status.tier())
        }
        assertTrue(ToolOutcomeStatus.entries.all { it.tier() != ToolOutcomeTier.UNAVAILABLE || it in unavailableStatuses })
    }
}
