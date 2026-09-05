package com.simone.jarvismobile.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
