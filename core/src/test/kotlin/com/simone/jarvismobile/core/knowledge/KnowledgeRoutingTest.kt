package com.simone.jarvismobile.core.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeRoutingTest {

    // --- category detection -----------------------------------------------

    @Test
    fun `networking questions are detected`() {
        assertEquals(KnowledgeCategory.NETWORKING, KnowledgeCategorizer.detect("Cos'è una VLAN?"))
        assertEquals(KnowledgeCategory.NETWORKING, KnowledgeCategorizer.detect("Come funziona il DHCP?"))
    }

    @Test
    fun `cybersecurity beats generic tech`() {
        assertEquals(
            KnowledgeCategory.CYBERSECURITY,
            KnowledgeCategorizer.detect("Cos'è un attacco di SQL injection?"),
        )
    }

    @Test
    fun `linux and science and biography route correctly`() {
        assertEquals(KnowledgeCategory.LINUX, KnowledgeCategorizer.detect("Come configurare SSH su Linux"))
        assertEquals(KnowledgeCategory.SCIENCE, KnowledgeCategorizer.detect("Spiegami la fotosintesi"))
        assertEquals(KnowledgeCategory.BIOGRAPHY, KnowledgeCategorizer.detect("Chi era Alan Turing?"))
    }

    @Test
    fun `an ordinary question is general`() {
        assertEquals(KnowledgeCategory.GENERAL, KnowledgeCategorizer.detect("Raccontami qualcosa di interessante"))
    }

    // --- source selection --------------------------------------------------

    @Test
    fun `cybersecurity searches security corpora before wikipedia`() {
        val corpora = KnowledgeSourceSelector.corpora(KnowledgeCategory.CYBERSECURITY, italianQuery = true)
        assertTrue(corpora.indexOf(CorpusKind.CYBERSECURITY) < corpora.indexOf(CorpusKind.WIKIPEDIA_IT))
        assertTrue(CorpusKind.USER in corpora)
    }

    @Test
    fun `an italian general query prefers italian wikipedia, english still available`() {
        val corpora = KnowledgeSourceSelector.corpora(KnowledgeCategory.GENERAL, italianQuery = true)
        assertTrue(corpora.indexOf(CorpusKind.WIKIPEDIA_IT) < corpora.indexOf(CorpusKind.WIKIPEDIA_EN))
    }

    @Test
    fun `a language question goes to wiktionary first`() {
        val corpora = KnowledgeSourceSelector.corpora(KnowledgeCategory.LANGUAGE, italianQuery = true)
        assertEquals(CorpusKind.WIKTIONARY, corpora.first { it != CorpusKind.USER })
    }

    // --- query expansion ---------------------------------------------------

    @Test
    fun `a described protocol expands to its acronym`() {
        val e = KnowledgeQueryExpander.expand("Come funziona il protocollo che assegna automaticamente gli IP?")
        assertTrue(e.primary.contains("DHCP"), "expected DHCP in: ${e.primary}")
    }

    @Test
    fun `an acronym expands to its full name for lexical search`() {
        val e = KnowledgeQueryExpander.expand("Cos'è il DNS?")
        assertTrue(e.primary.contains("Domain Name System"), "expected expansion in: ${e.primary}")
        assertTrue(e.terms.contains("dns"))
    }
}
