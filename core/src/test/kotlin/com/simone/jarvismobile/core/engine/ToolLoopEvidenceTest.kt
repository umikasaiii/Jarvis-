package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 2, §2 "evidence must
 * survive the tool loop" (JARVIS-05). Pins the exact previously-broken
 * behavior: round 2+ used to drop BOTH the original question and every
 * earlier round's results, keeping only the latest round's spoken text.
 */
class ToolLoopEvidenceTest {

    private fun r(name: String, status: ToolOutcomeStatus?, spoken: String) =
        ToolLoopEvidence.RoundResult(name, status, spoken)

    // § test 8 — original question survives at least a multi-round tool path.
    @Test
    fun `buildContinuation always includes the original question verbatim`() {
        val text = ToolLoopEvidence.buildContinuation(
            "Che tempo fa domani e ho impegni?",
            listOf(r("get_weather", ToolOutcomeStatus.SUCCESS_DATA, "Domani è sereno, 20 gradi.")),
        )
        assertTrue(text.contains("Che tempo fa domani e ho impegni?"))
    }

    // § test 9 — accumulated evidence survives multiple tool results, not just the latest round.
    @Test
    fun `buildContinuation includes every accumulated round result, not only the latest`() {
        val accumulated = listOf(
            r("get_weather", ToolOutcomeStatus.SUCCESS_DATA, "Domani è sereno, 20 gradi."),
            r("get_health_summary", ToolOutcomeStatus.SUCCESS_EMPTY, "Non ho dati di sonno questa settimana."),
        )
        val text = ToolLoopEvidence.buildContinuation("domanda originale", accumulated)
        assertTrue(text.contains("Domani è sereno, 20 gradi."))
        assertTrue(text.contains("Non ho dati di sonno questa settimana."))
    }

    @Test
    fun `buildContinuation never silently drops a result added earlier in the loop`() {
        // Simulates round 1 finding WEATHER, round 2 finding AGENDA — a
        // caller that (like the old, buggy code) rebuilt this text from only
        // the latest round would lose the weather result here.
        val round1 = listOf(r("get_weather", ToolOutcomeStatus.SUCCESS_DATA, "Sereno, 20 gradi."))
        val round2 = round1 + r("list_agenda", ToolOutcomeStatus.SUCCESS_DATA, "Hai una riunione alle 10.")
        val text = ToolLoopEvidence.buildContinuation("meteo e impegni di domani", round2)
        assertTrue(text.contains("Sereno, 20 gradi."))
        assertTrue(text.contains("riunione alle 10"))
    }

    // § test 2 (presentation angle) — SUCCESS_EMPTY gets a deterministic controlled path.
    @Test
    fun `deterministicEmptyPresentationOrNull returns the tool's own spoken text when every result is SUCCESS_EMPTY`() {
        val results = listOf(r("get_health_summary", ToolOutcomeStatus.SUCCESS_EMPTY, "Non ho registrato dati di sonno."))
        assertEquals("Non ho registrato dati di sonno.", ToolLoopEvidence.deterministicEmptyPresentationOrNull(results))
    }

    @Test
    fun `deterministicEmptyPresentationOrNull is null when any result is real data, never masks SUCCESS_DATA behind an empty message`() {
        val results = listOf(
            r("get_health_summary", ToolOutcomeStatus.SUCCESS_EMPTY, "Non ho dati di sonno."),
            r("get_weather", ToolOutcomeStatus.SUCCESS_DATA, "Sereno, 20 gradi."),
        )
        assertNull(ToolLoopEvidence.deterministicEmptyPresentationOrNull(results))
    }

    @Test
    fun `deterministicEmptyPresentationOrNull is null for a legacy unknown-status result, never guesses`() {
        assertNull(ToolLoopEvidence.deterministicEmptyPresentationOrNull(listOf(r("legacy_tool", null, "Fatto."))))
    }

    @Test
    fun `deterministicEmptyPresentationOrNull is null with no results at all`() {
        assertNull(ToolLoopEvidence.deterministicEmptyPresentationOrNull(emptyList()))
    }

    // § §6 — model failure after successful evidence collection must not erase it.
    @Test
    fun `presentAccumulatedOrNull surfaces usable evidence when the model itself becomes unavailable`() {
        val results = listOf(r("get_weather", ToolOutcomeStatus.SUCCESS_DATA, "Sereno, 20 gradi."))
        assertEquals("Sereno, 20 gradi.", ToolLoopEvidence.presentAccumulatedOrNull(results))
    }

    @Test
    fun `presentAccumulatedOrNull includes PARTIAL results, never hides the successful portion`() {
        val results = listOf(r("multi_source", ToolOutcomeStatus.PARTIAL, "Ho trovato il meteo, ma non i tuoi impegni."))
        assertEquals("Ho trovato il meteo, ma non i tuoi impegni.", ToolLoopEvidence.presentAccumulatedOrNull(results))
    }

    @Test
    fun `presentAccumulatedOrNull is null when every result is a bare failure - nothing presentable`() {
        val results = listOf(r("get_weather", ToolOutcomeStatus.SOURCE_FAILURE, "Non riesco ad accedere al meteo."))
        assertNull(ToolLoopEvidence.presentAccumulatedOrNull(results))
    }

    @Test
    fun `presentAccumulatedOrNull ignores unknown-status legacy results defensively, never presents them as authoritative`() {
        assertNull(ToolLoopEvidence.presentAccumulatedOrNull(listOf(r("legacy_tool", null, "Fatto."))))
    }

    @Test
    fun `presentAccumulatedOrNull mixes a real failure and a real success, keeps only the success`() {
        val results = listOf(
            r("get_weather", ToolOutcomeStatus.SOURCE_FAILURE, "Non riesco ad accedere al meteo."),
            r("get_health_summary", ToolOutcomeStatus.SUCCESS_DATA, "Hai dormito 7 ore."),
        )
        val presented = ToolLoopEvidence.presentAccumulatedOrNull(results)
        assertEquals("Hai dormito 7 ore.", presented)
    }

    @Test
    fun `presentAccumulatedOrNull is null with no results at all`() {
        assertNull(ToolLoopEvidence.presentAccumulatedOrNull(emptyList()))
    }
}
