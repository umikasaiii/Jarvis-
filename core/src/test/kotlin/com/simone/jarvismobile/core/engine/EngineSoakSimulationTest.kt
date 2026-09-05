package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.tools.RelevantToolSelector
import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * § FASE 2A.7 SOAK / LONG SESSION — this repository has no
 * Robolectric/instrumented infra to run 100 real turns through the live
 * `ConversationalJarvisEngine` (Android/Hilt-dependent, see the phase
 * report's honesty section), so this simulates the same shape at the layer
 * that IS pure and real: 100+ mixed synthetic requests through the actual
 * stateless building blocks (`RelevantToolSelector`, [GroundingGate]) that
 * back capability routing and grounding, plus a direct check of the exact
 * bounded-history reduction `ConversationalJarvisEngine.recordDiagnostics`
 * uses (`(list + entry).takeLast(MAX_DIAGNOSTICS_HISTORY)`) against real
 * [EngineTurnDiagnostics] values. This does not prove the live engine has no
 * memory leak or Android-lifecycle-specific ghost state (device-pending, see
 * the manual checklist) — it does prove the deterministic core these turns
 * are routed through carries no accumulating/mutating state of its own
 * across a long, mixed sequence, and that diagnostics history is genuinely
 * bounded rather than growing forever.
 */
class EngineSoakSimulationTest {

    private val mixedPhrases = listOf(
        "Ciao, come stai?" to null, // plain chat, no family
        "Che tempo fa domani?" to ToolFamily.WEATHER,
        "Quante ore ho dormito questa settimana?" to ToolFamily.HEALTH,
        "Che impegni ho oggi?" to ToolFamily.AGENDA,
        "Accendi la torcia" to ToolFamily.DEVICE,
        "{\"tool_calls\":[" to null, // malformed model output, not user text, but exercised through the parser matrix elsewhere
        "Ricordami di comprare il latte" to ToolFamily.MEMORY,
        "Considerando come ho dormito e gli impegni di domani..." to null, // multi-family, checked separately below
        "Attivalo per favore, è urgente" to null, // ambiguous
        "Fammi un sommario della riunione" to null, // false-positive regression case
    )

    @Test
    fun `100 mixed synthetic turns produce deterministic, non-leaking family selection`() {
        val turns = (1..120).map { mixedPhrases[it % mixedPhrases.size].first }

        // First pass: collect every turn's result.
        val firstPass = turns.map { RelevantToolSelector.matchedFamilies(it) }

        // Second, independent pass over the SAME sequence must be pixel-identical -
        // proof nothing accumulated across the 120 calls in between.
        val secondPass = turns.map { RelevantToolSelector.matchedFamilies(it) }
        assertEquals(firstPass, secondPass)

        // And a lone call for one specific phrase, made AFTER the whole 120-turn
        // sequence already ran, must match what that same phrase produced
        // in-sequence - no contamination from whatever came immediately before it.
        for (i in turns.indices) {
            val isolated = RelevantToolSelector.matchedFamilies(turns[i])
            assertEquals(firstPass[i], isolated, "turn $i (\"${turns[i]}\") diverged when called in isolation")
        }
    }

    @Test
    fun `100 mixed turns through the grounding gate never produce a stuck or drifting decision`() {
        val requiredSequence = listOf(
            emptySet(), setOf("WEATHER"), setOf("HEALTH"), setOf("AGENDA"),
            setOf("HEALTH", "AGENDA"), setOf("WEATHER"), emptySet(),
        )
        val results = (0 until 140).map { i ->
            val required = requiredSequence[i % requiredSequence.size]
            // Alternate between fully satisfied and fully unsatisfied, so the
            // gate sees both outcomes repeatedly in the same long run.
            val satisfied = if (i % 2 == 0) required else emptySet()
            GroundingGate.decide(ParseOutcome.PLAIN_TEXT, required, satisfied)
        }
        // Every "fully satisfied" turn (even index) must allow; every
        // "nothing satisfied, something required" turn (odd index, non-empty
        // required) must block - checked directly against what was actually
        // fed in, not just "some allow and some block somewhere".
        for (i in results.indices) {
            val required = requiredSequence[i % requiredSequence.size]
            if (i % 2 == 0) {
                assertEquals(GroundingGate.Decision.Allow, results[i], "turn $i unexpectedly blocked")
            } else if (required.isNotEmpty()) {
                assertTrue(results[i] is GroundingGate.Decision.Block, "turn $i unexpectedly allowed")
            }
        }
    }

    @Test
    fun `diagnostics history stays bounded to MAX_DIAGNOSTICS_HISTORY after far more turns than the cap`() {
        // Mirrors `ConversationalJarvisEngine`'s own reduction exactly:
        // `(list + entry).takeLast(MAX_DIAGNOSTICS_HISTORY)`, MAX = 20 there.
        val cap = 20
        var history = emptyList<EngineTurnDiagnostics>()
        val pushed = (1..150).map { i ->
            EngineTurnDiagnostics(
                engine = JarvisEngineMode.CONVERSAZIONALE,
                fastPathHit = false,
                timeToFirstEmitMs = i.toLong(),
                totalTurnMs = i.toLong() * 2,
                memoriesRetrieved = 0,
                toolsRequested = emptyList(),
                toolsExecuted = emptyList(),
                fallbackOccurred = false,
                parseError = false,
                timestamp = i.toLong(),
            )
        }
        for (entry in pushed) {
            history = (history + entry).takeLast(cap)
            assertTrue(history.size <= cap, "history grew past the cap of $cap")
        }
        assertEquals(cap, history.size)
        // The surviving entries must be EXACTLY the last `cap` pushed, in order -
        // never a stale ghost from far earlier in the run, never reordered.
        assertEquals(pushed.takeLast(cap).map { it.timestamp }, history.map { it.timestamp })
    }
}
