package com.simone.jarvismobile.core.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M2 closure — a passage that names the property but does not state its value is RELATED/insufficient, never the proof of a value. */
class M2SufficiencyTest {
    private fun chunk(title: String, text: String, section: String = "", lead: Boolean = true) =
        KnowledgeChunk("doc:$title", title, section.ifEmpty { title }, text, lead = lead)

    private fun supported(q: String, vararg c: KnowledgeChunk): Boolean =
        (EvidenceAssembler().assemble(q, emptyList(), c.toList(), 5) as? Evidence.Grounded)?.supportEstablished == true

    private val q = "In che anno fu costruito il Ponte di Calvera?"

    @Test fun datePresentAndAssociatedIsSupported() =
        assertTrue(supported(q, chunk("Ponte di Calvera", "Il Ponte di Calvera fu costruito nel 1388 dai mercanti del luogo.")))

    @Test fun unknownDateIsNotAValue() {
        assertFalse(supported(q, chunk("Ponte di Calvera", "Il Ponte di Calvera è un ponte medievale. Non si conosce l'anno della sua costruzione.")))
        assertFalse(supported(q, chunk("Ponte di Calvera", "Il Ponte di Calvera conserva archi romani di cui non si conosce l'anno in cui furono costruiti.")))
        assertFalse(supported(q, chunk("Ponte di Calvera", "L'anno di costruzione del Ponte di Calvera è sconosciuto.")))
        assertFalse(supported(q, chunk("Ponte di Calvera", "La data di costruzione del Ponte di Calvera è incerta e non è documentata.")))
    }

    @Test fun negatedDateIsNotAValueButTheCorrectedOneIs() {
        assertFalse(supported(q, chunk("Ponte di Calvera", "Il Ponte di Calvera non fu costruito nel 1500.")))
        assertTrue(supported(q, chunk("Ponte di Calvera", "Il Ponte di Calvera non fu costruito nel 1500, ma nel 1388.")))
    }

    @Test fun aNumberInsideAnIdentifierIsNotAValue() {
        assertFalse(supported("In che anno fu aperta la stazione Linea4412?", chunk("Stazione Linea4412", "La stazione Linea4412 è una piccola fermata ferroviaria.")))
        assertFalse(supported("In che anno fu costruito il ponte romano antico?", chunk("Comune 042", "Il comune di Frazione042 conserva un ponte romano antico costruito in epoca incerta.")))
    }

    @Test fun aNumberOfAnotherPropertyIsNotAYear() {
        assertFalse(supported(q, chunk("Ponte di Calvera", "Il Ponte di Calvera è lungo 82 metri e largo 6.")))
        assertFalse(supported(q, chunk("Ponte di Calvera", "Il Ponte di Calvera ha 12 arcate e ospita 4000 abitanti.")))
        // …and a year is not a measure
        assertFalse(supported("Quanto è lungo il Ponte di Calvera?", chunk("Ponte di Calvera", "Il Ponte di Calvera fu costruito nel 1388.")))
    }

    @Test fun insufficientAndSupportingPassagesOfTheSameSubjectStayDistinct() {
        val lead = chunk("Ponte di Calvera", "Il Ponte di Calvera è un ponte medievale. Non si conosce l'anno della sua costruzione.")
        val storia = chunk("Ponte di Calvera", "Il ponte di Calvera fu costruito nel 1388.", section = "Storia", lead = false)
        val g = EvidenceAssembler().assemble(q, emptyList(), listOf(lead, storia), 5) as Evidence.Grounded
        assertTrue(g.supportEstablished)
        val withValue = g.passages.filter { "1388" in it.supportWindow }
        assertTrue(withValue.isNotEmpty() && withValue.all { it.chunk.section == "Storia" })
        assertFalse(g.passages.any { "1388" in it.supportWindow && it.chunk === lead })
    }

    @Test fun trulyAbsentInformationIsNotSupported() {
        assertFalse(supported(q, chunk("Torre di Milvana", "La Torre di Milvana è una torre di guardia costruita nel 1210.")))
        assertFalse(supported(q))
    }

    @Test fun anotherEntityWithTheSameWordsDoesNotAnswer() {
        assertFalse(supported("In che anno fu costruito il Faro di Brenda?", chunk("Faro di Ostrena", "Il Faro di Ostrena fu costruito nel 1632.")))
        assertTrue(supported("In che anno fu costruito il Faro di Ostrena?", chunk("Faro di Ostrena", "Il Faro di Ostrena fu costruito nel 1632.")))
    }

    @Test fun missingTermIsCompletedOnlyFromTheSameArticle() {
        val lead = chunk("Cardonia", "La cardonia è un organo muscolare legato al ritmo della pulsazione veloce.")
        val sec = chunk("Cardonia", "In condizioni normali regola il ritmo con il nodo sinuale.", section = "Funzione", lead = false)
        val other = KnowledgeChunk("doc:Altro", "Altro", "Funzione", "Un altro organo regola il ritmo del respiro.")
        val g = EvidenceAssembler().assemble("Quale organo regola il ritmo della pulsazione veloce?", emptyList(), listOf(lead, sec, other), 5) as Evidence.Grounded
        assertTrue(g.passages.any { it.chunk === sec })
        assertFalse(g.passages.any { it.chunk === other && it.supportWindow.isNotBlank() && !it.related && g.passages.none { p -> p.chunk === sec } })
    }

    @Test fun adjectiveAndQualityNounAreACandidateNeverProof() {
        val lead = chunk("Vulcano Orvella", "Il dato fondamentale è l'altezza di 2.140 metri.", lead = true)
        val g = EvidenceAssembler().assemble("Quanto è alto il Vulcano Orvella?", emptyList(), listOf(lead), 5)
        val related = (g as? Evidence.Grounded)?.passages.orEmpty()
        assertFalse((g as? Evidence.Grounded)?.supportEstablished == true)
        assertTrue(related.all { it.related })
    }

    @Test fun numberWordsAndStandaloneNumbers() {
        assertTrue(supported("Quanto è profondo il Lago Perlane?", chunk("Lago Perlane", "La profondità massima del Lago Perlane è di ottantadue metri.")))
        assertEquals(true, SupportJudge.withholdsValue("Non è noto quando fu fondata."))
        assertEquals(false, SupportJudge.withholdsValue("Fu fondata nel 1388 da mercanti."))
    }
}

class M2PartialFramingTest {
    private fun frame(state: com.simone.jarvismobile.core.reliability.RetrievalState) =
        com.simone.jarvismobile.core.reliability.AnswerFraming.qualifier(com.simone.jarvismobile.core.reliability.RequestKind.FACTUAL, state, preciseFacts = false)

    @Test fun absenceIsNeverClaimedWhilePartOfTheSearchDidNotRun() {
        val partial = frame(com.simone.jarvismobile.core.reliability.RetrievalState.NO_RESULT_PARTIAL)
        assertTrue("non posso dire che l'informazione sia assente" in partial)
        assertEquals("", frame(com.simone.jarvismobile.core.reliability.RetrievalState.NO_RESULT)) // complete search, no precise facts: existing behaviour
    }

    @Test fun reportStatesAreDistinct() {
        val ok = RetrievalReport(1, 0, true, false, false, false, 0, emptyList())
        val failedSource = ok.copy(sourcesFailed = 1)
        val indexPartial = ok.copy(indexPartial = true)
        val allDown = RetrievalReport(2, 2, false, true, false, false, 0, listOf("source:x"))
        assertFalse(ok.partial); assertTrue(failedSource.partial); assertTrue(indexPartial.partial); assertTrue(allDown.unreadable)
        assertFalse(failedSource.unreadable)
    }
}
