package com.simone.jarvismobile.core.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DocumentQueryParserTest {
    private fun p(s: String) = DocumentQueryParser.parse(s)

    @Test fun summarizeThisPdf() = assertEquals(DocumentQueryIntent.Summarize(DocRef.Latest), p("Riassumi questo PDF."))
    @Test fun summarizeJustImported() = assertEquals(DocumentQueryIntent.Summarize(DocRef.Latest), p("Riassumi il PDF che ho appena importato"))
    @Test fun summarizeNamed() = assertIs<DocRef.Named>((p("Riassumi il manuale della moto") as DocumentQueryIntent.Summarize).doc)

    @Test fun whatDoesTheManualSay() {
        val i = p("Cosa dice il manuale sulla manutenzione?") as DocumentQueryIntent.Search
        assertEquals("manutenzione", i.topic); assertEquals(SearchScope.DOCUMENTS, i.scope); assertIs<DocRef.Named>(i.doc)
    }

    @Test fun searchMyDocumentsForExpiry() {
        val i = p("Cerca nei miei documenti quando scade l'assicurazione") as DocumentQueryIntent.Search
        assertEquals(SearchScope.DOCUMENTS, i.scope); assertEquals(null, i.doc)
        assertEquals(true, "scade" in i.topic || "assicurazione" in i.topic)
    }

    @Test fun consultMyDocumentsHasNoTopicYet() {
        val i = p("Consulta i miei documenti") as DocumentQueryIntent.Search
        assertEquals("", i.topic)
    }

    @Test fun searchNotesForATopic() {
        val i = p("Cerca nei miei appunti tutte le informazioni sull'argomento moto") as DocumentQueryIntent.Search
        assertEquals(SearchScope.NOTES, i.scope); assertEquals("moto", i.topic)
    }

    @Test fun whatInfoDoYouHaveSearchesEverything() {
        val i = p("Che informazioni hai sulla mia assicurazione?") as DocumentQueryIntent.Search
        assertEquals(SearchScope.ALL, i.scope)
    }

    @Test fun compareTwoDocuments() {
        val i = p("Confronta il documento A con il documento B") as DocumentQueryIntent.Compare
        assertEquals(DocRef.Named("A"), i.a); assertEquals(DocRef.Named("B"), i.b)
    }

    @Test fun differencesBetween() {
        val i = p("Quali differenze ci sono tra i documenti Preventivo1 e Preventivo2?") as DocumentQueryIntent.Compare
        assertEquals(DocRef.Named("Preventivo1"), i.a); assertEquals(DocRef.Named("Preventivo2"), i.b)
    }

    @Test fun whichPage() {
        val i = p("In quale pagina si parla del problema?") as DocumentQueryIntent.PageLookup
        assertEquals("problema", i.topic)
    }

    @Test fun ordinaryChatIsNeverCaptured() {
        listOf(
            "Ciao come stai", "Riassumi questo testo: il gatto è sul tavolo e il cane dorme", "Cosa dice il meteo",
            "Quanto fa 2+2", "Crea una nota chiamata Idee", "Ricordami domani di chiamare Luca", "Confronta i prezzi",
            "Che ore sono", "Cerca una pizzeria",
        ).forEach { assertNull(p(it), it) }
    }
}
