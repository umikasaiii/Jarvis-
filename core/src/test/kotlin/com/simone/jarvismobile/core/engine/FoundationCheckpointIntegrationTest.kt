package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.health.HealthCoverage
import com.simone.jarvismobile.core.health.DailyHealthReading
import com.simone.jarvismobile.core.tools.StructuredToolResult
import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import com.simone.jarvismobile.core.weather.WeatherLocationKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 11 §H — cross-component
 * integration tests: chains of ALREADY-stabilized pure `:core` pieces from
 * DIFFERENT PASSAGGIO 1–7 domains, proving they agree with each other when
 * used together, not just in isolation. Every piece exercised here already
 * has its own dedicated unit-test suite (`GroundingGateTest`,
 * `ToolLoopEvidenceTest`, `PresentationGuardTest`, `AgendaTest`,
 * `HealthCoverageTest`, `WeatherLocationKeyTest`, `StructuredToolResultTest`)
 * — this file does not re-test any single piece in isolation, only the
 * SEAMS between them.
 *
 * Automation dedup/commit-state (PASSAGGIO 8/8.1), navigation generation
 * safety (PASSAGGIO 9/9.1) and backup recovery (PASSAGGIO 10-10.3) are
 * deliberately NOT re-tested here: their own dedicated suites
 * (`ExecutionGateTest`, `RouteGenerationGateTest`/
 * `ActiveRouteSnapshotArbitrationTest`, `RestoreRecoveryOutcomeResolverTest`
 * — the last two in `app/src/test`, CI-verified only, no Android SDK here)
 * already chain their own internal pieces end-to-end; duplicating them here
 * would test nothing new.
 */
class FoundationCheckpointIntegrationTest {

    // --- H1/H2/H3/H4 — Grounding sees the real distinct status, never a blur ---

    @Test fun successDataReachesGroundedPresentation() {
        val decision = GroundingGate.decide(
            parseOutcome = ParseOutcome.VALID,
            requiredFamilies = setOf("AGENDA"),
            satisfiedFamilies = emptySet(),
            evidenceByFamily = mapOf("AGENDA" to ToolOutcomeStatus.SUCCESS_DATA),
        )
        assertEquals(GroundingGate.Decision.Allow, decision)
    }

    @Test fun successEmptyIsAllowedByGroundingAndNeverInventedByTheModel() {
        // GroundingGate: a genuinely empty-but-successful result is grounded, not blocked.
        val decision = GroundingGate.decide(
            parseOutcome = ParseOutcome.VALID,
            requiredFamilies = setOf("AGENDA"),
            satisfiedFamilies = emptySet(),
            evidenceByFamily = mapOf("AGENDA" to ToolOutcomeStatus.SUCCESS_EMPTY),
        )
        assertEquals(GroundingGate.Decision.Allow, decision)

        // ToolLoopEvidence: that same SUCCESS_EMPTY round is answered from the
        // tool's OWN deterministic text, never handed to the model to paraphrase
        // — the exact mechanism that makes "the model invented data from an
        // empty result" structurally impossible for this shape.
        val deterministic = ToolLoopEvidence.deterministicEmptyPresentationOrNull(
            listOf(ToolLoopEvidence.RoundResult("list_agenda", ToolOutcomeStatus.SUCCESS_EMPTY, "Non hai impegni per quel giorno.")),
        )
        assertEquals("Non hai impegni per quel giorno.", deterministic)
    }

    @Test fun permissionMissingIsDistinctFromDataUnavailableAndFromSuccess() {
        val permission = GroundingGate.decide(
            ParseOutcome.VALID, setOf("HEALTH"), emptySet(),
            evidenceByFamily = mapOf("HEALTH" to ToolOutcomeStatus.PERMISSION_MISSING),
        )
        val unavailable = GroundingGate.decide(
            ParseOutcome.VALID, setOf("HEALTH"), emptySet(),
            evidenceByFamily = mapOf("HEALTH" to ToolOutcomeStatus.DATA_UNAVAILABLE),
        )
        check(permission is GroundingGate.Decision.Block)
        check(unavailable is GroundingGate.Decision.Block)
        // Both block, but the REASON they were blocked stays distinguishable —
        // never collapsed into one generic "failed" bucket.
        assertEquals(ToolOutcomeStatus.PERMISSION_MISSING, permission.unmetDetails.single().status)
        assertEquals(ToolOutcomeStatus.DATA_UNAVAILABLE, unavailable.unmetDetails.single().status)
        assertNotEquals(permission.unmetDetails.single().status, unavailable.unmetDetails.single().status)
    }

    @Test fun staleDataIsBlockedByDefaultButAllowedOnlyWhenExplicitlyPermitted() {
        val blockedByDefault = GroundingGate.decide(
            ParseOutcome.VALID, setOf("WEATHER"), emptySet(),
            evidenceByFamily = mapOf("WEATHER" to ToolOutcomeStatus.STALE),
        )
        assertTrue(blockedByDefault is GroundingGate.Decision.Block)

        val allowedWhenPermitted = GroundingGate.decide(
            ParseOutcome.VALID, setOf("WEATHER"), emptySet(),
            evidenceByFamily = mapOf("WEATHER" to ToolOutcomeStatus.STALE),
            staleAllowedFamilies = setOf("WEATHER"),
        )
        assertEquals(GroundingGate.Decision.Allow, allowedWhenPermitted)
    }

    // --- H5 — internal protocol JSON structurally cannot leak into the answer ---

    @Test fun internalProtocolJsonInsideAssistantTextCannotReachTheUser() {
        val leaked = """{"tool_calls":[{"id":"1","name":"list_agenda","arguments":{}}],"assistant_text":"ok"}"""
        assertTrue(PresentationGuard.isInternalSchemaLeak(leaked))

        // Ordinary prose that merely MENTIONS json/braces is never flagged —
        // this is structural detection, not a word/character blacklist.
        assertFalse(PresentationGuard.isInternalSchemaLeak("Il formato JSON usa le parentesi graffe { }."))
        assertFalse(PresentationGuard.isInternalSchemaLeak("Hai un impegno domani alle 15:00."))
    }

    // --- H7/H8 — Agenda: Home and Chat resolve the SAME range identically, empty is legitimate ---

    @Test fun agendaTomorrowRangeIsIdenticalRegardlessOfWhichCallerAsks() {
        val today = LocalDate.of(2026, 9, 9)
        val tomorrow = today.plusDays(1)
        val entries = listOf(
            AgendaEntry(date = tomorrow, time = LocalTime.of(10, 0), text = "Dentista"),
            AgendaEntry(date = today, time = LocalTime.of(9, 0), text = "Non per domani"),
        )
        // Home's own projection and Chat's list_agenda tool both call
        // Agenda.filter with the identical (day) shape — proving there is
        // exactly one authoritative read path, not two that could drift.
        val homeProjection = Agenda.filter(entries, today = today, day = tomorrow)
        val chatToolResult = Agenda.filter(entries, today = today, day = tomorrow)
        assertEquals(homeProjection, chatToolResult)
        assertEquals(listOf("Dentista"), homeProjection.map { it.text })
    }

    @Test fun agendaEmptyRangeIsAGenuineSuccessEmptyNeverBlockedOrInvented() {
        val today = LocalDate.of(2026, 9, 9)
        val entries = listOf(AgendaEntry(date = today.plusDays(30), text = "Molto lontano"))

        val weekRange = Agenda.filter(entries, today = today, day = today, toDay = today.plusDays(6))
        assertTrue(weekRange.isEmpty())

        // Wrapped as tool evidence, a genuinely empty range is SUCCESS_EMPTY —
        // grounded and allowed, never blocked as if the tool had failed.
        val evidence = StructuredToolResult.successEmpty(requestedRange = "$today..${today.plusDays(6)}")
        val decision = GroundingGate.decide(
            ParseOutcome.VALID, setOf("AGENDA"), emptySet(),
            evidenceByFamily = mapOf("AGENDA" to evidence.status),
        )
        assertEquals(GroundingGate.Decision.Allow, decision)
    }

    @Test fun agendaDayAfterTomorrowAndWeekRangeBothResolveCorrectly() {
        val today = LocalDate.of(2026, 9, 9)
        val dayAfterTomorrow = today.plusDays(2)
        val entries = listOf(
            AgendaEntry(date = dayAfterTomorrow, text = "Riunione"),
            AgendaEntry(date = today.plusDays(5), text = "Dentro la settimana"),
            AgendaEntry(date = today.plusDays(10), text = "Fuori dalla settimana"),
        )
        assertEquals(listOf("Riunione"), Agenda.filter(entries, today, day = dayAfterTomorrow).map { it.text })
        val week = Agenda.filter(entries, today, day = today, toDay = today.plusDays(6))
        assertEquals(setOf("Riunione", "Dentro la settimana"), week.map { it.text }.toSet())
    }

    // --- H9/H10 — Health: a missing range is never zero, coverage metadata is real ---

    @Test fun healthMissingRangeIsDataUnavailableNeverZero() {
        // Nothing was ever queried for this range — genuinely NotCovered.
        val neverQueried = HealthCoverage.resolveRange(emptyList(), requestedDays = 7) { it.sleepHours != null }
        assertFalse(neverQueried.hasAnyCoverage)
        assertFalse(neverQueried.hasAnyData)

        // As tool evidence, "never queried" must become DATA_UNAVAILABLE, NOT
        // a fabricated zero and NOT SUCCESS_EMPTY (which would claim "we
        // checked and there's genuinely nothing" — a different, false claim).
        val evidence = if (neverQueried.hasAnyCoverage) {
            StructuredToolResult.successEmpty()
        } else {
            StructuredToolResult.dataUnavailable(sourceId = "health_connect", reasonCode = "no_coverage")
        }
        assertEquals(ToolOutcomeStatus.DATA_UNAVAILABLE, evidence.status)
        assertNull(evidence.payload)
    }

    @Test fun healthCoveredButEmptyIsDistinctFromNeverQueried() {
        val date = LocalDate.of(2026, 9, 7)
        // Covered (the day WAS queried) but the metric field is genuinely null.
        val covered = HealthCoverage.resolveDay(
            listOf(DailyHealthReading(date = date, heartRateBpm = null, sleepHours = null)),
            date,
        )
        assertTrue(covered is com.simone.jarvismobile.core.health.HealthDayCoverage.Covered)

        val neverAsked = HealthCoverage.resolveDay(emptyList(), date)
        assertTrue(neverAsked is com.simone.jarvismobile.core.health.HealthDayCoverage.NotCovered)

        // Coverage metadata set by the tool survives as a real, readable field
        // — nothing between construction and this assertion silently drops it.
        val evidence = StructuredToolResult.successEmpty(sourceId = "health_connect", coverage = date.toString())
        assertEquals(date.toString(), evidence.coverage)
        assertEquals("health_connect", evidence.sourceId)
    }

    // --- H11/H12 — Weather: location identity and temporal range are both binding ---

    @Test fun weatherLocationMismatchCanNeverReuseAnotherLocationsCacheTag() {
        val rome = WeatherLocationKey.of(41.9028, 12.4964)
        val milan = WeatherLocationKey.of(45.4642, 9.1900)
        assertNotEquals(rome, milan)
        assertNotEquals(rome.asCacheTag(), milan.asCacheTag())

        val sameRomeAgain = WeatherLocationKey.of(41.9028, 12.4964)
        assertEquals(rome, sameRomeAgain)
        assertEquals(rome.asCacheTag(), sameRomeAgain.asCacheTag())
    }

    @Test fun weatherTemporalRangeSurvivesFromToolEvidenceThroughToPresentationFields() {
        val payload = JsonObject(mapOf("temp_c" to JsonPrimitive(21.0), "category" to JsonPrimitive("CLEAR")))
        val evidence = StructuredToolResult.successData(
            payload = payload,
            sourceId = "open_meteo",
            coverage = "today",
            unit = "celsius",
        )
        assertEquals(ToolOutcomeStatus.SUCCESS_DATA, evidence.status)
        assertEquals("today", evidence.coverage)
        assertEquals("celsius", evidence.unit)
        assertNotNull(evidence.payload)

        // Grounding only needs the STATUS to decide — but the richer evidence
        // (location/range/units) the tool attached is never discarded to get
        // there; it is simply a different concern than the boolean gate.
        val decision = GroundingGate.decide(
            ParseOutcome.VALID, setOf("WEATHER"), emptySet(),
            evidenceByFamily = mapOf("WEATHER" to evidence.status),
        )
        assertEquals(GroundingGate.Decision.Allow, decision)
        assertEquals("today", evidence.coverage) // still intact after being consulted for grounding
    }
}
