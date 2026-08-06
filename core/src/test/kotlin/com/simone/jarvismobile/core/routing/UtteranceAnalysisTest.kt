package com.simone.jarvismobile.core.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtteranceAnalysisTest {
    @Test
    fun `splits the two real tool questions`() {
        assertEquals(
            listOf("Che ore sono ora?", "Quanto fa 5+6?"),
            CompoundRequestSplitter.split("Che ore sono ora? Quanto fa 5+6?"),
        )
    }

    @Test
    fun `splits two related knowledge questions`() {
        assertEquals(
            listOf("Come si taglia l'erba?", "Di quali attrezzi ho bisogno?"),
            CompoundRequestSplitter.split("Come si taglia l'erba? Di quali attrezzi ho bisogno?"),
        )
    }

    @Test
    fun `does not split a single compound sentence`() {
        assertEquals(
            listOf("Qual è la differenza tra tagliaerba elettrico e manuale?"),
            CompoundRequestSplitter.split("Qual è la differenza tra tagliaerba elettrico e manuale?"),
        )
    }

    @Test
    fun `reasoning fallback recognises instructions and estimates`() {
        assertTrue(ComplexityHeuristic.needsReasoning("Come si taglia l'erba?"))
        assertTrue(ComplexityHeuristic.needsReasoning("Quanto mi richiede indicativamente?"))
        assertFalse(ComplexityHeuristic.needsReasoning("Che ore sono?"))
    }
}
