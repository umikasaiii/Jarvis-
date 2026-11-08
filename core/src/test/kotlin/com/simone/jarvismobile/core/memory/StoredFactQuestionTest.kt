package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BETA 2/8 — routing of memory questions vs plain conversation / agenda / documents. */
class StoredFactQuestionTest {
    @Test fun recognisesQuestionsAboutWhatTheUserToldJarvis() {
        listOf(
            "Qual è la mia targa?", "qual era il nome del mio gatto", "come si chiama la mia dentista",
            "ti avevo detto dove ho parcheggiato", "ti ricordi la mia password del wifi", "dove ho messo le chiavi",
            "cosa avevo deciso per il weekend", "avevamo deciso il ristorante", "quando è il compleanno di mia sorella? il mio",
        ).forEach { assertTrue(StoredFactQuestion.matches(it), it) }
    }

    @Test fun plainConversationIsNotTreatedAsAMemoryQuestion() {
        listOf(
            "qual è la capitale della Francia?", "la mia domanda è: cos'è la fotosintesi?", "ciao come stai",
            "scrivimi una poesia sul mare", "quanto fa 12 per 7", "che ore sono",
        ).forEach { assertFalse(StoredFactQuestion.matches(it), it) }
    }

    @Test fun groundingNoteForbidsInventingWhenNothingWasFound() {
        val none = StoredFactQuestion.groundingNote(hasRelevantNotes = false)
        assertTrue("non c'è alcun appunto" in none && "Non inventare" in none)
        val some = StoredFactQuestion.groundingNote(hasRelevantNotes = true)
        assertTrue("USANDO SOLO" in some && "non inventare" in some)
    }

    @Test fun existingMemoryRouterStillSeparatesTheOtherSources() {
        assertEquals(MemoryRoute.DOCUMENT_SEARCH, MemoryRouter.classify("cosa dice il documento sul contratto"))
        assertEquals(MemoryRoute.KNOWLEDGE_SEARCH, MemoryRouter.classify("spiegami come funziona il motore"))
        assertEquals(MemoryRoute.NONE, MemoryRouter.classify("12 + 30"))
        assertEquals(MemoryRoute.PERSONAL_MEMORY, MemoryRouter.classify("ti avevo detto il codice"))
    }
}
