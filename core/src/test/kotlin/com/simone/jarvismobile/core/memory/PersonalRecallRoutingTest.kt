package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hotfix post-M4 H1: questions about something the user told JARVIS stay on personal memory; ordinary questions still reach the library. */
class PersonalRecallRoutingTest {
    private val equivalent = listOf(
        "Qual è il codice del mio progetto di prova?",
        "Come avevo chiamato il progetto di prova?",
        "Ti ricordi il nome in codice del progetto?",
        "Qual era il codice che ti avevo detto di salvare?",
    )

    @Test fun everyEquivalentFormulationIsAStoredFactQuestion() = equivalent.forEach { assertTrue(StoredFactQuestion.matches(it), it) }

    @Test fun aPersonalQuestionSkipsTheLibraryWhenMemoryHasAHitOrTheQuestionIsExplicitlyPersonal() {
        equivalent.forEach { assertTrue(StoredFactQuestion.skipEncyclopedia(it, hasLexicalMemoryHit = true), "hit: $it") }
        // nothing stored: an explicit personal marker still keeps Wikibooks "progetti universitari" from standing in for the missing fact
        listOf(equivalent[0], equivalent[1], equivalent[3]).forEach { assertTrue(StoredFactQuestion.skipEncyclopedia(it, false), "marker: $it") }
    }

    @Test fun ordinaryQuestionsAreNotPersonalJustBecauseOfAWord() {
        listOf("Ricordi il teorema di Pitagora?", "Qual è la capitale del Portogallo?", "Che cos'è un progetto in informatica?", "Spiegami la memoria RAM",
            "Quanto fa 37 per 24?").forEach { assertFalse(StoredFactQuestion.skipEncyclopedia(it, false), it) }
        // a memory hit alone never turns a non-personal sentence into a personal one
        assertFalse(StoredFactQuestion.skipEncyclopedia("Che cos'è un progetto in informatica?", true))
    }

    @Test fun retrievalFindsTheSameFactWithDifferentWording() {
        val chunk = MemoryChunk("local://memoria/1", "Memoria", "Il codice del mio progetto di prova è Orione.", folder = "memoria")
        val noise = listOf(
            MemoryChunk("local://memoria/2", "Memoria", "Il progetto di tesi consegna a giugno.", folder = "memoria"),
            MemoryChunk("local://memoria/3", "Memoria", "La targa della moto finisce con 42.", folder = "memoria"),
        )
        val ranker = RetrievalRanker()
        equivalent.forEach { q ->
            val top = ranker.rank(q, noise + chunk, 3).firstOrNull()?.chunk?.text
            assertEquals(chunk.text, top, "top hit for: $q")
        }
        assertTrue(ranker.rank("Qual è il codice del mio progetto di prova?", noise, 3).none { "Orione" in it.chunk.text }, "a deleted note is not retrieved")
    }

    @Test fun aSavedNoteIsEvidenceOnlyForQuestionsAboutTheUser() {
        equivalent.forEach { assertTrue(StoredFactQuestion.isAboutUser(it), it) }
        listOf("Come mi chiamo?", "Cosa sai di me?", "Qual è il mio colore preferito?").forEach { assertTrue(StoredFactQuestion.isAboutUser(it), it) }
        listOf("Che cos'è un progetto?", "Qual è la capitale del Portogallo?", "Spiegami cos'è una variabile in Python").forEach { assertFalse(StoredFactQuestion.isAboutUser(it), it) }
    }
}
