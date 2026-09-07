package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * § FASE 2A.7 RELEASE GATE 8 — the fundamental grounding invariant, pinned
 * directly (not via a single global boolean): a request can require several
 * families at once, and every one of them must be satisfied by a REAL,
 * matching tool execution before an answer is allowed through.
 */
class GroundingGateTest {

    private fun decide(required: Set<String>, satisfied: Set<String>) =
        GroundingGate.decide(ParseOutcome.PLAIN_TEXT, required, satisfied)

    @Test
    fun `case A - required WEATHER, satisfied none - blocks`() {
        val result = decide(setOf("WEATHER"), emptySet())
        assertTrue(result is GroundingGate.Decision.Block)
        assertEquals("no_tool_call_for_required_family:WEATHER", (result as GroundingGate.Decision.Block).reason)
    }

    @Test
    fun `case B - required HEALTH, satisfied none - blocks`() {
        val result = decide(setOf("HEALTH"), emptySet())
        assertTrue(result is GroundingGate.Decision.Block)
    }

    @Test
    fun `case C - required HEALTH, satisfied HEALTH - allowed`() {
        assertEquals(GroundingGate.Decision.Allow, decide(setOf("HEALTH"), setOf("HEALTH")))
    }

    @Test
    fun `case D - required HEALTH, satisfied WEATHER - blocks (wrong family never satisfies)`() {
        val result = decide(setOf("HEALTH"), setOf("WEATHER"))
        assertTrue(result is GroundingGate.Decision.Block)
        assertEquals("no_tool_call_for_required_family:HEALTH", (result as GroundingGate.Decision.Block).reason)
    }

    @Test
    fun `case E - required HEALTH+AGENDA, satisfied only HEALTH - blocks, names the unmet one`() {
        val result = decide(setOf("HEALTH", "AGENDA"), setOf("HEALTH"))
        assertTrue(result is GroundingGate.Decision.Block)
        assertEquals("no_tool_call_for_required_family:AGENDA", (result as GroundingGate.Decision.Block).reason)
    }

    @Test
    fun `case F - required HEALTH+AGENDA, satisfied both - allowed`() {
        assertEquals(GroundingGate.Decision.Allow, decide(setOf("HEALTH", "AGENDA"), setOf("HEALTH", "AGENDA")))
    }

    @Test
    fun `nothing required is always allowed regardless of what was satisfied`() {
        assertEquals(GroundingGate.Decision.Allow, decide(emptySet(), emptySet()))
        assertEquals(GroundingGate.Decision.Allow, decide(emptySet(), setOf("WEATHER")))
    }

    @Test
    fun `malformed JSON blocks first, even when nothing was required at all`() {
        // § FASE 2A.6 §6 — the exact "raw JSON shown in chat" bug: a request
        // that never required ANY grounded family (e.g. the DEVICE family is
        // not in this call's required set) must still never surface a
        // malformed protocol fragment.
        val result = GroundingGate.decide(ParseOutcome.MALFORMED_JSON, emptySet(), emptySet())
        assertEquals(GroundingGate.Decision.Block(GroundingGate.MALFORMED_JSON_REASON), result)
    }

    @Test
    fun `malformed JSON blocks even when every required family was already satisfied`() {
        // Precedence: malformed output is checked BEFORE grounding, unconditionally.
        val result = GroundingGate.decide(ParseOutcome.MALFORMED_JSON, setOf("HEALTH"), setOf("HEALTH"))
        assertEquals(GroundingGate.Decision.Block(GroundingGate.MALFORMED_JSON_REASON), result)
    }

    @Test
    fun `multiple unmet families are all named, not just the first`() {
        val result = decide(setOf("HEALTH", "AGENDA", "WEATHER"), emptySet())
        assertTrue(result is GroundingGate.Decision.Block)
        val reason = (result as GroundingGate.Decision.Block).reason
        assertTrue(reason.contains("HEALTH"))
        assertTrue(reason.contains("AGENDA"))
        assertTrue(reason.contains("WEATHER"))
    }

    @Test
    fun `a plain VALID or REPAIRED outcome with satisfied grounding is allowed`() {
        assertEquals(
            GroundingGate.Decision.Allow,
            GroundingGate.decide(ParseOutcome.VALID, setOf("AGENDA"), setOf("AGENDA")),
        )
        assertEquals(
            GroundingGate.Decision.Allow,
            GroundingGate.decide(ParseOutcome.REPAIRED, setOf("AGENDA"), setOf("AGENDA")),
        )
    }

    // ============================================================
    // § JARVIS Implementation Master Plan — PASSAGGIO 2, §1 STRUCTURED
    // GROUNDING: `decide()` consuming real ToolOutcomeStatus evidence per
    // family, instead of only the boolean satisfiedFamilies set.
    // ============================================================

    private fun decideWithEvidence(
        required: Set<String>,
        satisfied: Set<String> = emptySet(),
        evidence: Map<String, ToolOutcomeStatus>,
        staleAllowed: Set<String> = emptySet(),
    ) = GroundingGate.decide(ParseOutcome.PLAIN_TEXT, required, satisfied, evidence, staleAllowed)

    @Test
    fun `test 1 - SUCCESS_DATA satisfies grounding`() {
        val result = decideWithEvidence(setOf("WEATHER"), evidence = mapOf("WEATHER" to ToolOutcomeStatus.SUCCESS_DATA))
        assertEquals(GroundingGate.Decision.Allow, result)
    }

    @Test
    fun `test 2 - SUCCESS_EMPTY is successful grounding, never failure`() {
        val result = decideWithEvidence(setOf("HEALTH"), evidence = mapOf("HEALTH" to ToolOutcomeStatus.SUCCESS_EMPTY))
        assertEquals(GroundingGate.Decision.Allow, result)
    }

    @Test
    fun `test 3 - PERMISSION_MISSING remains distinguishable and blocks`() {
        val result = decideWithEvidence(setOf("HEALTH"), evidence = mapOf("HEALTH" to ToolOutcomeStatus.PERMISSION_MISSING))
        assertTrue(result is GroundingGate.Decision.Block)
        val details = (result as GroundingGate.Decision.Block).unmetDetails
        assertEquals(1, details.size)
        assertEquals(ToolOutcomeStatus.PERMISSION_MISSING, details.single().status)
    }

    @Test
    fun `test 4 - SOURCE_FAILURE is not empty, blocks distinctly from PERMISSION_MISSING`() {
        val result = decideWithEvidence(setOf("WEATHER"), evidence = mapOf("WEATHER" to ToolOutcomeStatus.SOURCE_FAILURE))
        assertTrue(result is GroundingGate.Decision.Block)
        assertEquals(ToolOutcomeStatus.SOURCE_FAILURE, (result as GroundingGate.Decision.Block).unmetDetails.single().status)
    }

    @Test
    fun `test 5a - STALE blocks when the family does not explicitly allow it`() {
        val result = decideWithEvidence(setOf("WEATHER"), evidence = mapOf("WEATHER" to ToolOutcomeStatus.STALE))
        assertTrue(result is GroundingGate.Decision.Block)
        assertEquals(ToolOutcomeStatus.STALE, (result as GroundingGate.Decision.Block).unmetDetails.single().status)
    }

    @Test
    fun `test 5b - STALE is allowed once the family explicitly opts in`() {
        val result = decideWithEvidence(
            setOf("WEATHER"),
            evidence = mapOf("WEATHER" to ToolOutcomeStatus.STALE),
            staleAllowed = setOf("WEATHER"),
        )
        assertEquals(GroundingGate.Decision.Allow, result)
    }

    @Test
    fun `test 6 - PARTIAL preserves the successful evidence, satisfies grounding`() {
        val result = decideWithEvidence(setOf("AGENDA"), evidence = mapOf("AGENDA" to ToolOutcomeStatus.PARTIAL))
        assertEquals(GroundingGate.Decision.Allow, result)
    }

    @Test
    fun `test 7 - legacy family with no evidence still falls back to the boolean check, never invents empty`() {
        // No entry in evidenceByFamily at all for AGENDA — exactly the ~125
        // not-yet-migrated tool call sites' shape.
        val blocked = decideWithEvidence(setOf("AGENDA"), satisfied = emptySet(), evidence = emptyMap())
        assertTrue(blocked is GroundingGate.Decision.Block)
        assertNull((blocked as GroundingGate.Decision.Block).unmetDetails.singleOrNull()?.status)

        val allowed = decideWithEvidence(setOf("AGENDA"), satisfied = setOf("AGENDA"), evidence = emptyMap())
        assertEquals(GroundingGate.Decision.Allow, allowed)
    }

    @Test
    fun `DATA_UNAVAILABLE and TOOL_FAILURE both block, distinctly`() {
        val unavailable = decideWithEvidence(setOf("HEALTH"), evidence = mapOf("HEALTH" to ToolOutcomeStatus.DATA_UNAVAILABLE))
        val toolFailure = decideWithEvidence(setOf("HEALTH"), evidence = mapOf("HEALTH" to ToolOutcomeStatus.TOOL_FAILURE))
        assertTrue(unavailable is GroundingGate.Decision.Block)
        assertTrue(toolFailure is GroundingGate.Decision.Block)
        assertEquals(ToolOutcomeStatus.DATA_UNAVAILABLE, (unavailable as GroundingGate.Decision.Block).unmetDetails.single().status)
        assertEquals(ToolOutcomeStatus.TOOL_FAILURE, (toolFailure as GroundingGate.Decision.Block).unmetDetails.single().status)
    }

    @Test
    fun `mixed multi-family evidence blocks only the unmet ones, by real status`() {
        val result = decideWithEvidence(
            setOf("HEALTH", "WEATHER"),
            evidence = mapOf("HEALTH" to ToolOutcomeStatus.SUCCESS_DATA, "WEATHER" to ToolOutcomeStatus.SOURCE_FAILURE),
        )
        assertTrue(result is GroundingGate.Decision.Block)
        val details = (result as GroundingGate.Decision.Block).unmetDetails
        assertEquals(listOf(GroundingGate.UnmetFamily("WEATHER", ToolOutcomeStatus.SOURCE_FAILURE)), details)
    }

    @Test
    fun `malformed JSON still blocks first even with rich structured evidence available`() {
        val result = GroundingGate.decide(
            ParseOutcome.MALFORMED_JSON,
            setOf("HEALTH"),
            setOf("HEALTH"),
            evidenceByFamily = mapOf("HEALTH" to ToolOutcomeStatus.SUCCESS_DATA),
        )
        assertEquals(GroundingGate.Decision.Block(GroundingGate.MALFORMED_JSON_REASON), result)
    }
}
