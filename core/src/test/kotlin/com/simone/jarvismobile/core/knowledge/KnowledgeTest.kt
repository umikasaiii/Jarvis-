package com.simone.jarvismobile.core.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KnowledgeIndexerTest {

    @Test
    fun `headings become sections`() {
        val doc = """
            # Manutenzione moto

            Testo introduttivo abbastanza lungo da restare un passaggio a sé stante
            e non venire fuso con quello successivo dal passaggio di merge.

            ## Cambio gomme

            Solleva la moto con il cavalletto centrale prima di rimuovere la ruota.
        """.trimIndent()

        val chunks = KnowledgeIndexer.chunk("guide:moto.md", "Manuale moto", doc)
        assertEquals(listOf("Manutenzione moto", "Cambio gomme"), chunks.map { it.section })
        assertTrue(chunks.last().text.contains("cavalletto"))
    }

    @Test
    fun `long passages are split on sentence boundaries`() {
        val sentence = "Questa e una frase di prova sufficientemente lunga da riempire spazio. "
        val chunks = KnowledgeIndexer.chunk("g", "G", "# S\n" + sentence.repeat(40))
        assertTrue(chunks.size > 1, "un testo lungo deve produrre piu passaggi")
        assertTrue(chunks.all { it.text.length <= KnowledgeIndexer.MAX_CHARS })
        // No word was cut in half.
        assertTrue(chunks.none { it.text.endsWith("fras") || it.text.endsWith("suffi") })
    }

    @Test
    fun `citation names the document and the section`() {
        val c = KnowledgeChunk("id", "Manuale moto", "Cambio gomme", "…")
        assertEquals("Manuale moto, Cambio gomme", c.citation)
        assertEquals("Manuale moto", c.copy(section = "").citation)
    }
}

class KnowledgeSearchTest {

    private val library = KnowledgeIndexer.chunk(
        "guide:moto.md",
        "Manuale moto",
        """
        # Cambio gomme
        Per sostituire la gomma posteriore solleva la moto sul cavalletto,
        allenta i dadi dell'asse e sfila la ruota dal forcellone.

        # Catena
        La catena va lubrificata ogni cinquecento chilometri con grasso spray.

        # Revisione
        La revisione periodica si effettua presso un centro autorizzato.
        """.trimIndent(),
    )

    private val search = KnowledgeSearch()

    @Test
    fun `finds the passage that answers the question`() {
        val ev = search.search("come cambio la gomma della moto?", library)
        val grounded = assertIs<Evidence.Grounded>(ev)
        assertEquals("Cambio gomme", grounded.passages.first().chunk.section)
    }

    @Test
    fun `singular and plural still meet`() {
        val ev = search.search("devo cambiare le gomme", library)
        val grounded = assertIs<Evidence.Grounded>(ev)
        assertEquals("Cambio gomme", grounded.passages.first().chunk.section)
    }

    @Test
    fun `a topic that is not in the library returns no evidence`() {
        // This is the behaviour that stops JARVIS inventing an answer.
        assertIs<Evidence.NoEvidence>(search.search("quanto costa un volo per Tokyo?", library))
    }

    @Test
    fun `an empty library returns no evidence`() {
        assertIs<Evidence.NoEvidence>(search.search("come cambio la gomma?", emptyList()))
    }

    @Test
    fun `results are ordered by relevance and capped`() {
        val ev = search.search("catena lubrificata grasso", library, limit = 2)
        val grounded = assertIs<Evidence.Grounded>(ev)
        assertTrue(grounded.passages.size <= 2)
        assertEquals("Catena", grounded.passages.first().chunk.section)
        val scores = grounded.passages.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }
}
