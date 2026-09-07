package com.simone.jarvismobile.core.agenda

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 4 §3/§7/§11 — the exact
 * EMPTY-vs-FAILURE distinction the whole outcome taxonomy exists to
 * enforce, now for Agenda. `ListAgendaTool`/`AgendaRepository` themselves
 * (`app/`) are not directly JVM-testable here (Android/Hilt dependencies,
 * no Robolectric in this project) — this pins the pure mapping they both
 * ultimately rely on.
 */
class AgendaEvidenceTest {

    private val today = LocalDate.of(2026, 8, 6)
    private val sampleEntries = listOf(
        AgendaEntry(today, null, "colazione con Luca"),
        AgendaEntry(today.plusDays(1), null, "revisione auto"),
    )

    // § test 5 — SUCCESS_DATA preserves records/range metadata.
    @Test
    fun `a non-empty success produces SUCCESS_DATA with record ids and the requested range preserved`() {
        val evidence = AgendaEvidence.evidenceFor(
            AgendaQueryOutcome.Success(sampleEntries),
            requestedRange = "2026-08-06..2026-08-07",
            retrievedAt = 1000L,
        )
        assertEquals(ToolOutcomeStatus.SUCCESS_DATA, evidence.status)
        assertEquals("2026-08-06..2026-08-07", evidence.coverage)
        assertEquals(1000L, evidence.retrievedAt)
        val recordIds = evidence.payload?.get("record_ids")?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(sampleEntries.map { it.id }, recordIds)
        assertEquals(2, evidence.payload?.get("count")?.jsonPrimitive?.content?.toInt())
    }

    // § test 6 — real empty query → SUCCESS_EMPTY.
    @Test
    fun `a genuinely empty success produces SUCCESS_EMPTY, never confused with failure`() {
        val evidence = AgendaEvidence.evidenceFor(
            AgendaQueryOutcome.Success(emptyList()),
            requestedRange = "2026-08-06",
            retrievedAt = 2000L,
        )
        assertEquals(ToolOutcomeStatus.SUCCESS_EMPTY, evidence.status)
        assertEquals("2026-08-06", evidence.requestedRange)
        assertNotEquals(ToolOutcomeStatus.DATA_UNAVAILABLE, evidence.status)
        assertNotEquals(ToolOutcomeStatus.SOURCE_FAILURE, evidence.status)
    }

    // § test 7 — source failure never → SUCCESS_EMPTY.
    @Test
    fun `a real read failure produces SOURCE_FAILURE, never SUCCESS_EMPTY`() {
        val evidence = AgendaEvidence.evidenceFor(
            AgendaQueryOutcome.Failure("IOException"),
            requestedRange = "2026-08-06",
            retrievedAt = 3000L,
        )
        assertEquals(ToolOutcomeStatus.SOURCE_FAILURE, evidence.status)
        assertEquals("IOException", evidence.reasonCode)
        assertEquals(true, evidence.retryable)
        assertNotEquals(ToolOutcomeStatus.SUCCESS_EMPTY, evidence.status)
    }

    @Test
    fun `no revision is ever fabricated - storage does not provide one`() {
        val dataEvidence = AgendaEvidence.evidenceFor(AgendaQueryOutcome.Success(sampleEntries), "x", 1L)
        val emptyEvidence = AgendaEvidence.evidenceFor(AgendaQueryOutcome.Success(emptyList()), "x", 1L)
        val failureEvidence = AgendaEvidence.evidenceFor(AgendaQueryOutcome.Failure("x"), "x", 1L)
        assertNull(dataEvidence.revision)
        assertNull(emptyEvidence.revision)
        assertNull(failureEvidence.revision)
    }

    @Test
    fun `requestedRangeLabel reflects day and toDay, distinct from the human-facing spoken sentence`() {
        assertEquals(null, AgendaEvidence.requestedRangeLabel(null, null))
        assertEquals(today.toString(), AgendaEvidence.requestedRangeLabel(today, null))
        assertEquals("$today..${today.plusDays(6)}", AgendaEvidence.requestedRangeLabel(today, today.plusDays(6)))
    }

    @Test
    fun `source id is stable and identifies the local agenda store`() {
        val evidence = AgendaEvidence.evidenceFor(AgendaQueryOutcome.Success(sampleEntries), null, 1L)
        assertEquals(AgendaEvidence.SOURCE_ID, evidence.sourceId)
    }
}
