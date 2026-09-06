package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.tools.GROUNDED_FAMILIES
import com.simone.jarvismobile.core.tools.RelevantToolSelector
import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * § FASE 2A.10 test category H — a soak test combining ALL THREE layers this
 * phase touches (merge → route → tool-selection), extending
 * [SemanticEngineSoakSimulationTest] (FASE 2A.9, merge-only) with the two
 * pieces FASE 2A.10 added: [SemanticRouter] (FASE 2A.9.1) and
 * [RelevantToolSelector.select]'s `forcedFamilies` union (§5). ≥300 turns, a
 * fixed seed for determinism, mixing every intent/operation/domain shape the
 * spec names so the invariants hold at scale, not just for a handful of
 * hand-picked examples.
 */
class SemanticRoutingSoakTest {

    private val sampleTools: List<Pair<String, String>> = listOf(
        "get_weather" to "Meteo reale di oggi o dei prossimi giorni.",
        "get_health_summary" to "Riepilogo settimanale di sonno e frequenza cardiaca.",
        "list_agenda" to "Elenca gli impegni.",
        "get_device_info" to "Informazioni reali sul telefono.",
        "search_knowledge" to "Cerca nella conoscenza importata.",
        "calculate" to "Esegue un calcolo aritmetico.",
    )

    private fun frame(
        intent: SemanticIntent,
        domains: Set<ToolFamily>,
        operation: SemanticOperation,
        explicitDomain: Boolean,
        reference: ReferenceMode = ReferenceMode.NONE,
        confidence: Double = 0.9,
    ) = SemanticFrame(
        intent = intent,
        domains = domains,
        operation = operation,
        temporalExpression = null,
        metric = null,
        aggregation = null,
        entities = emptyList(),
        referenceMode = reference,
        requiresGrounding = domains.isNotEmpty(),
        confidence = confidence,
        explicitSlots = if (explicitDomain && domains.isNotEmpty()) setOf(SemanticSlot.DOMAINS) else emptySet(),
    )

    /** Deterministic seeded PRNG — no external dependency, reproducible across runs. */
    private class Seeded(private var seed: Long) {
        fun nextInt(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 33) % bound).toInt()
        }
    }

    /** One of every scenario §16 (and this phase's own §5/§9) name, cycling deterministically. */
    private fun scriptedTurns(count: Int): List<SemanticFrame> {
        val singleDomains = listOf(ToolFamily.WEATHER, ToolFamily.HEALTH, ToolFamily.AGENDA, ToolFamily.DEVICE_INFO)
        val rng = Seeded(7L)
        return (0 until count).map { i ->
            when (i % 8) {
                0 -> frame(SemanticIntent.CAPABILITY_QUERY, setOf(singleDomains[rng.nextInt(singleDomains.size)]), SemanticOperation.GET, explicitDomain = true)
                1 -> frame(SemanticIntent.CAPABILITY_QUERY, emptySet(), SemanticOperation.GET, explicitDomain = false, reference = ReferenceMode.ELLIPSIS)
                2 -> frame(SemanticIntent.MULTI_SOURCE_REASONING, setOf(ToolFamily.HEALTH, ToolFamily.AGENDA), SemanticOperation.GET, explicitDomain = true)
                3 -> frame(SemanticIntent.KNOWLEDGE_QUERY, setOf(ToolFamily.KNOWLEDGE), SemanticOperation.SEARCH, explicitDomain = true)
                4 -> frame(SemanticIntent.CONVERSATION, emptySet(), SemanticOperation.UNKNOWN, explicitDomain = false)
                5 -> frame(SemanticIntent.CAPABILITY_QUERY, setOf(ToolFamily.AGENDA), SemanticOperation.CREATE, explicitDomain = true) // non-read-only
                6 -> frame(SemanticIntent.CLARIFICATION, emptySet(), SemanticOperation.UNKNOWN, explicitDomain = false)
                else -> frame(SemanticIntent.UNKNOWN, emptySet(), SemanticOperation.UNKNOWN, explicitDomain = false, confidence = 0.2) // will fail validation
            }
        }
    }

    @Test
    fun `300+ mixed turns - merge, route and tool-selection never crash and always produce a well-typed outcome`() {
        var previous: SemanticFrame? = null
        var directCount = 0
        var handoffCount = 0
        var invalidCount = 0

        for (raw in scriptedTurns(300)) {
            when (val validated = SemanticFrameValidator.validate(raw)) {
                is SemanticInterpretation.Invalid -> {
                    invalidCount++
                    // An invalid frame is never merged and never advances
                    // `previous` — exactly LEGACY_FALLBACK's contract.
                }
                is SemanticInterpretation.Valid -> {
                    val merge = SemanticFrameMerger.merge(validated.frame, previous)
                    val frame = merge.frame
                    previous = frame

                    when (val outcome = SemanticRouter.routeFrame(frame)) {
                        is SemanticRoutingOutcome.Direct -> {
                            directCount++
                            assertTrue(outcome.domain in SemanticRouter.DIRECTLY_ROUTABLE_DOMAINS)
                            assertEquals(setOf(outcome.domain), frame.domains)
                        }
                        SemanticRoutingOutcome.HandoffToLlm -> {
                            handoffCount++
                            if (frame.intent == SemanticIntent.MULTI_SOURCE_REASONING) {
                                val forced = frame.domains.filter { it in GROUNDED_FAMILIES }.toSet()
                                val selected = RelevantToolSelector.select(sampleTools, "richiesta ambigua", forcedFamilies = forced)
                                val selectedFamilies = RelevantToolSelector.familiesOf(selected)
                                // Exactness: every forced family's tool is present.
                                assertTrue(selectedFamilies.containsAll(forced))
                            }
                        }
                    }
                }
            }
        }

        // All three outcome buckets are actually exercised — a soak that only
        // ever hits one branch would not be testing anything at scale.
        assertTrue(directCount > 0)
        assertTrue(handoffCount > 0)
        assertTrue(invalidCount > 0)
        assertEquals(300, directCount + handoffCount + invalidCount)
    }

    @Test
    fun `the same 300+ turn script always produces the same sequence of routing outcomes, deterministic under repetition`() {
        fun runOnce(): List<String> {
            var previous: SemanticFrame? = null
            val out = mutableListOf<String>()
            for (raw in scriptedTurns(300)) {
                when (val validated = SemanticFrameValidator.validate(raw)) {
                    is SemanticInterpretation.Invalid -> out += "INVALID"
                    is SemanticInterpretation.Valid -> {
                        val merge = SemanticFrameMerger.merge(validated.frame, previous)
                        previous = merge.frame
                        out += when (val outcome = SemanticRouter.routeFrame(merge.frame)) {
                            is SemanticRoutingOutcome.Direct -> "DIRECT:${outcome.domain}"
                            SemanticRoutingOutcome.HandoffToLlm -> "HANDOFF"
                        }
                    }
                }
            }
            return out
        }
        assertEquals(runOnce(), runOnce())
    }

    @Test
    fun `a non-read-only operation is never routed directly, across the whole soak`() {
        var previous: SemanticFrame? = null
        for (raw in scriptedTurns(300)) {
            val validated = SemanticFrameValidator.validate(raw) as? SemanticInterpretation.Valid ?: continue
            val merge = SemanticFrameMerger.merge(validated.frame, previous)
            previous = merge.frame
            if (merge.frame.operation !in READ_ONLY_OPERATIONS && merge.frame.operation != SemanticOperation.UNKNOWN) {
                assertEquals(SemanticRoutingOutcome.HandoffToLlm, SemanticRouter.routeFrame(merge.frame))
            }
        }
    }
}
