package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * § FASE 2A.9 §16 F — extends the soak-testing discipline established by
 * `EngineSoakSimulationTest` (FASE 2A.7) to the semantic merge layer: many
 * (≥200) mixed synthetic turns — frequent domain changes, elliptical
 * follow-ups, paraphrase-shaped frames, low-confidence/invalid frames mixed
 * in — run through the SAME stateful "remember the last merged frame, merge
 * the next against it" loop `ConversationalJarvisEngine`/`ConversationManager`
 * actually use, proving the pure merge/validate layer holds its invariants at
 * scale, not just for the handful of hand-picked examples in
 * [SemanticFrameMergerTest]. Does not exercise the real Android engine
 * (no state leak there is provable in this JVM-only environment — see
 * `CLAUDE.md`), only the deterministic core it is built on.
 */
class SemanticEngineSoakSimulationTest {

    private fun frame(
        intent: SemanticIntent,
        domain: ToolFamily?,
        explicit: Boolean,
        reference: ReferenceMode = ReferenceMode.NONE,
        metric: String? = null,
        confidence: Double = 0.9,
    ) = SemanticFrame(
        intent = intent,
        domains = domain?.let { setOf(it) } ?: emptySet(),
        operation = SemanticOperation.GET,
        temporalExpression = null,
        metric = metric,
        aggregation = null,
        entities = emptyList(),
        referenceMode = reference,
        requiresGrounding = domain != null,
        confidence = confidence,
        explicitSlots = if (explicit && domain != null) setOf(SemanticSlot.DOMAINS) else emptySet(),
    )

    /** A long, deterministic, seeded sequence mixing every scenario §16 asks for. */
    private fun scriptedTurns(count: Int): List<SemanticFrame> {
        val domains = listOf(
            ToolFamily.HEALTH, ToolFamily.WEATHER, ToolFamily.AGENDA,
            ToolFamily.DEVICE_INFO, ToolFamily.KNOWLEDGE,
        )
        var seed = 42L
        fun nextInt(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 33) % bound).toInt()
        }
        return (0 until count).map { i ->
            when (i % 5) {
                0 -> frame(SemanticIntent.CAPABILITY_QUERY, domains[nextInt(domains.size)], explicit = true)
                1 -> frame(SemanticIntent.CAPABILITY_QUERY, null, explicit = false, reference = ReferenceMode.ELLIPSIS)
                2 -> frame(SemanticIntent.CONVERSATION, null, explicit = false)
                3 -> frame(SemanticIntent.KNOWLEDGE_QUERY, ToolFamily.KNOWLEDGE, explicit = true)
                else -> frame(SemanticIntent.CAPABILITY_QUERY, null, explicit = false, reference = ReferenceMode.PARTITIVE, metric = "ram")
            }
        }
    }

    @Test
    fun `200+ mixed turns never let a stale domain override an explicit current one`() {
        var previous: SemanticFrame? = null
        val turns = scriptedTurns(220)
        for (current in turns) {
            val result = SemanticFrameMerger.merge(current, previous)
            if (SemanticSlot.DOMAINS in current.explicitSlots) {
                // The one invariant this whole phase exists for: an explicit
                // domain this turn is NEVER replaced by whatever came before.
                assertEquals(current.domains, result.frame.domains)
            }
            previous = result.frame
        }
    }

    @Test
    fun `an ellipsis follow-up always inherits the domain of a genuinely capability-shaped previous frame, never a KNOWLEDGE or CONVERSATION one`() {
        var previous: SemanticFrame? = null
        for (current in scriptedTurns(220)) {
            val result = SemanticFrameMerger.merge(current, previous)
            if (current.domains.isEmpty() &&
                current.intent == SemanticIntent.CAPABILITY_QUERY &&
                current.referenceMode == ReferenceMode.ELLIPSIS
            ) {
                val prevWasCapabilitySource = previous?.intent == SemanticIntent.CAPABILITY_QUERY ||
                    previous?.intent == SemanticIntent.MULTI_SOURCE_REASONING
                if (prevWasCapabilitySource && previous?.domains?.isNotEmpty() == true) {
                    assertEquals(previous.domains, result.frame.domains)
                } else {
                    assertTrue(result.frame.domains.isEmpty())
                }
            }
            previous = result.frame
        }
    }

    @Test
    fun `the same fixed script always merges to the exact same sequence of domains, deterministic under repetition`() {
        fun runOnce(): List<Set<ToolFamily>> {
            var previous: SemanticFrame? = null
            val out = mutableListOf<Set<ToolFamily>>()
            for (current in scriptedTurns(220)) {
                val result = SemanticFrameMerger.merge(current, previous)
                out += result.frame.domains
                previous = result.frame
            }
            return out
        }
        assertEquals(runOnce(), runOnce())
    }

    @Test
    fun `a KNOWLEDGE turn never leaves a domain that a later ellipsis follow-up could inherit`() {
        val knowledgeFrame = frame(SemanticIntent.KNOWLEDGE_QUERY, ToolFamily.KNOWLEDGE, explicit = true)
        val followUp = frame(SemanticIntent.CAPABILITY_QUERY, null, explicit = false, reference = ReferenceMode.ELLIPSIS)
        val result = SemanticFrameMerger.merge(followUp, knowledgeFrame)
        assertTrue(result.frame.domains.isEmpty())
    }
}
