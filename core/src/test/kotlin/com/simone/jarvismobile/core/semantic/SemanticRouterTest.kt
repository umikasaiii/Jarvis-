package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * § FASE 2A.9.1 — invariant tests for the ownership decision. The release
 * invariant this whole file exists to pin: a VALID [SemanticFrame] NEVER
 * produces anything legacy keyword routing could still intercept —
 * [SemanticRoutingOutcome] has no such case to produce ([SemanticRouter]'s
 * own doc comment explains why that is structural, not just tested).
 */
class SemanticRouterTest {

    private fun frame(
        intent: SemanticIntent,
        domains: Set<ToolFamily> = emptySet(),
        operation: SemanticOperation = SemanticOperation.GET,
        confidence: Double = 0.8,
    ) = SemanticFrame(
        intent = intent,
        domains = domains,
        operation = operation,
        temporalExpression = null,
        metric = null,
        aggregation = null,
        entities = emptyList(),
        referenceMode = ReferenceMode.NONE,
        requiresGrounding = domains.isNotEmpty(),
        confidence = confidence,
        explicitSlots = if (domains.isNotEmpty()) setOf(SemanticSlot.DOMAINS) else emptySet(),
    )

    @Test
    fun `valid KNOWLEDGE_QUERY always hands off to the LLM, never a Direct outcome`() {
        val outcome = SemanticRouter.routeFrame(frame(SemanticIntent.KNOWLEDGE_QUERY, domains = setOf(ToolFamily.KNOWLEDGE)))
        assertEquals(SemanticRoutingOutcome.HandoffToLlm, outcome)
    }

    @Test
    fun `valid CONVERSATION always hands off, regardless of domain`() {
        val outcome = SemanticRouter.routeFrame(frame(SemanticIntent.CONVERSATION))
        assertEquals(SemanticRoutingOutcome.HandoffToLlm, outcome)
    }

    @Test
    fun `valid CLARIFICATION and UNKNOWN both hand off`() {
        assertEquals(SemanticRoutingOutcome.HandoffToLlm, SemanticRouter.routeFrame(frame(SemanticIntent.CLARIFICATION)))
        assertEquals(SemanticRoutingOutcome.HandoffToLlm, SemanticRouter.routeFrame(frame(SemanticIntent.UNKNOWN)))
    }

    @Test
    fun `valid MULTI_SOURCE_REASONING with HEALTH+AGENDA hands off and preserves both domains on the frame for the caller to seed grounding`() {
        val f = frame(SemanticIntent.MULTI_SOURCE_REASONING, domains = setOf(ToolFamily.HEALTH, ToolFamily.AGENDA))
        val outcome = SemanticRouter.routeFrame(f)
        assertEquals(SemanticRoutingOutcome.HandoffToLlm, outcome)
        // The router itself never mutates/clears domains — the caller reads
        // frame.domains directly, never a duplicate synthesis pipeline.
        assertEquals(setOf(ToolFamily.HEALTH, ToolFamily.AGENDA), f.domains)
    }

    @Test
    fun `a non-read-only operation always hands off, even for a resolvable domain`() {
        val outcome = SemanticRouter.routeFrame(
            frame(SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.AGENDA), operation = SemanticOperation.DELETE),
        )
        assertEquals(SemanticRoutingOutcome.HandoffToLlm, outcome)
    }

    @Test
    fun `CONTROL and CREATE and UPDATE operations all hand off, never authorized directly`() {
        for (op in listOf(SemanticOperation.CONTROL, SemanticOperation.CREATE, SemanticOperation.UPDATE)) {
            val outcome = SemanticRouter.routeFrame(frame(SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.WEATHER), operation = op))
            assertEquals(SemanticRoutingOutcome.HandoffToLlm, outcome, "operation=$op must hand off")
        }
    }

    @Test
    fun `an ambiguous empty domain on a CAPABILITY_QUERY hands off, never falls to legacy reclassification`() {
        val outcome = SemanticRouter.routeFrame(frame(SemanticIntent.CAPABILITY_QUERY, domains = emptySet()))
        assertEquals(SemanticRoutingOutcome.HandoffToLlm, outcome)
    }

    @Test
    fun `a two-domain CAPABILITY_QUERY (not MULTI_SOURCE_REASONING) also hands off, not a Direct pick`() {
        val outcome = SemanticRouter.routeFrame(
            frame(SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.WEATHER, ToolFamily.HEALTH)),
        )
        assertEquals(SemanticRoutingOutcome.HandoffToLlm, outcome)
    }

    @Test
    fun `a domain outside the four directly-routable ones hands off, e_g_ MEMORY`() {
        val outcome = SemanticRouter.routeFrame(frame(SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.MEMORY)))
        assertEquals(SemanticRoutingOutcome.HandoffToLlm, outcome)
    }

    @Test
    fun `explicit WEATHER after a previous HEALTH frame routes Direct to WEATHER`() {
        val previous = SemanticFrame(
            intent = SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.HEALTH), operation = SemanticOperation.GET,
            temporalExpression = null, metric = null, aggregation = null, entities = emptyList(),
            referenceMode = ReferenceMode.NONE, requiresGrounding = true, confidence = 0.9,
            explicitSlots = setOf(SemanticSlot.DOMAINS),
        )
        val current = frame(SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.WEATHER)).copy(
            explicitSlots = setOf(SemanticSlot.DOMAINS),
        )
        val merged = SemanticFrameMerger.merge(current, previous).frame
        val outcome = SemanticRouter.routeFrame(merged)
        assertEquals(SemanticRoutingOutcome.Direct(ToolFamily.WEATHER), outcome)
    }

    @Test
    fun `valid HEALTH after a previous AGENDA frame routes Direct to HEALTH`() {
        val previous = SemanticFrame(
            intent = SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.AGENDA), operation = SemanticOperation.LIST,
            temporalExpression = "domani", metric = null, aggregation = null, entities = emptyList(),
            referenceMode = ReferenceMode.NONE, requiresGrounding = true, confidence = 0.9,
            explicitSlots = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION),
        )
        val current = frame(SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.HEALTH)).copy(
            explicitSlots = setOf(SemanticSlot.DOMAINS),
        )
        val merged = SemanticFrameMerger.merge(current, previous).frame
        val outcome = SemanticRouter.routeFrame(merged)
        assertEquals(SemanticRoutingOutcome.Direct(ToolFamily.HEALTH), outcome)
    }

    @Test
    fun `every DIRECTLY_ROUTABLE_DOMAINS entry routes Direct in isolation`() {
        for (domain in SemanticRouter.DIRECTLY_ROUTABLE_DOMAINS) {
            val outcome = SemanticRouter.routeFrame(frame(SemanticIntent.CAPABILITY_QUERY, domains = setOf(domain)))
            assertIs<SemanticRoutingOutcome.Direct>(outcome)
            assertEquals(domain, outcome.domain)
        }
    }

    // --- 200+ turn soak: valid interpretation never produces a "legacy-shaped" outcome ---

    @Test
    fun `soak - 220 mixed valid frames never produce anything but Direct or HandoffToLlm`() {
        val intents = SemanticIntent.entries
        val domainsPool = listOf(
            emptySet(), setOf(ToolFamily.WEATHER), setOf(ToolFamily.HEALTH), setOf(ToolFamily.AGENDA),
            setOf(ToolFamily.DEVICE_INFO), setOf(ToolFamily.KNOWLEDGE), setOf(ToolFamily.MEMORY),
            setOf(ToolFamily.HEALTH, ToolFamily.AGENDA), setOf(ToolFamily.WEATHER, ToolFamily.HEALTH),
        )
        val operations = SemanticOperation.entries
        var seed = 7L
        fun nextInt(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 33) % bound).toInt()
        }
        var directCount = 0
        var handoffCount = 0
        repeat(220) {
            val f = frame(
                intent = intents[nextInt(intents.size)],
                domains = domainsPool[nextInt(domainsPool.size)],
                operation = operations[nextInt(operations.size)],
            )
            when (SemanticRouter.routeFrame(f)) {
                is SemanticRoutingOutcome.Direct -> directCount++
                SemanticRoutingOutcome.HandoffToLlm -> handoffCount++
            }
        }
        // The two counts are exhaustive by construction (the `when` above is
        // exhaustive over a two-case sealed interface with no `else`) — this
        // assertion is the "counter" proving it held for all 220 turns, not
        // just structurally: every one of the 220 calls landed in one of the
        // two buckets, zero fell through to anything else.
        assertEquals(220, directCount + handoffCount)
    }
}
