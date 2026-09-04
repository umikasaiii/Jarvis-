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
    fun `splits several related questions even when commas and conjunctions are used`() {
        assertEquals(
            listOf(
                "Quanto costa un tagliando moto",
                "quanto dura",
                "ogni quanto va fatto",
                "ne ho uno in programma?",
            ),
            CompoundRequestSplitter.split(
                "Quanto costa un tagliando moto, quanto dura e ogni quanto va fatto, ne ho uno in programma?",
            ),
        )
    }

    @Test
    fun `splits two explicit actions joined by e`() {
        assertEquals(
            listOf("Accendi la torcia", "imposta un timer di cinque minuti"),
            CompoundRequestSplitter.split("Accendi la torcia e imposta un timer di cinque minuti"),
        )
    }

    @Test
    fun `turn plan marks later clauses as context dependent`() {
        val plan = TurnPlanner.plan("Quanto costa un tagliando, quanto dura?")
        assertTrue(plan.isCompound)
        assertFalse(plan.requests.first().carriesTurnContext)
        assertTrue(plan.requests.last().carriesTurnContext)
    }

    @Test
    fun `reasoning fallback recognises instructions and estimates`() {
        assertTrue(ComplexityHeuristic.needsReasoning("Come si taglia l'erba?"))
        assertTrue(ComplexityHeuristic.needsReasoning("Quanto mi richiede indicativamente?"))
        assertFalse(ComplexityHeuristic.needsReasoning("Che ore sono?"))
    }

    @Test
    fun `ordinary multi question chat skips the model tool classifier`() {
        assertFalse(
            ToolIntentGate.shouldClassify(
                "Come taglio l'erba? Che attrezzi dovrei usare? Quanto tempo richiede?",
            ),
        )
        assertFalse(ToolIntentGate.shouldClassify("Ho mangiato un panino ieri."))
    }

    @Test
    fun `possible natural language operations keep the classifier available`() {
        assertTrue(ToolIntentGate.shouldClassify("Potresti segnarti che lunedì ho il dentista?"))
        assertTrue(ToolIntentGate.shouldClassify("È in carica in questo momento?"))
    }

    @Test
    fun `a personal-task statement without agenda words is still tool-shaped - FASE 2A5`() {
        // § root cause of "Settimana prossima devo comprare qualcosa?" never
        // reaching any tool: RelevantToolSelector's first gate is this same
        // shouldClassify() - it returned false for this phrase (no keyword
        // matched at all), so the FAST prompt told the model NO tool was
        // needed, foreclosing list_agenda/add_task before the model could
        // even try. "devo" (first person, present - "I need/must to…") is
        // the standard Italian way to state a personal task, the same shape
        // as an agenda/reminder check even without the words agenda/impegno.
        assertTrue(ToolIntentGate.shouldClassify("Settimana prossima devo comprare qualcosa?"))
        assertTrue(ToolIntentGate.shouldClassify("Devo chiamare il dentista domani."))
    }

    @Test
    fun `an advice-seeking conditional question still skips the classifier - FASE 2A5`() {
        // "dovrei"/"dovevo" (conditional/past) read as advice-seeking, not a
        // task statement - must NOT be swept in by the "devo" fix above, or
        // this pins that the existing multi-question reasoning test above
        // would have broken.
        assertFalse(ToolIntentGate.shouldClassify("Cosa dovrei fare per rilassarmi?"))
    }

    @Test
    fun `reply cleaner removes role prefixes and generated user continuation`() {
        assertEquals(
            "Ti serve un tagliaerba.",
            AssistantReplyCleaner.clean(
                "Tu: Ti serve un tagliaerba.\nSimone: E quanto tempo?\nTu: Due ore.",
            ),
        )
        assertEquals("La risposta utile.", AssistantReplyCleaner.clean("Risposta: La risposta utile."))
        // A mid-reply "Tu:" line is the model role-playing the user; drop it.
        assertEquals(
            "Mi piace il gelato.",
            AssistantReplyCleaner.clean("Mi piace il gelato.\nTu: Posso salvare: «Mi piace il gelato»."),
        )
    }
}
