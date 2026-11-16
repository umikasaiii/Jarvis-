package com.simone.jarvismobile.core.reliability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M0 closure: residual P0 findings of the independent audit of 205ccc5 (synthetic fixtures; no product-side answers). */
class ReliabilityClosureTest {
    private fun raw(title: String, text: String, key: String, kind: EvidenceKind = EvidenceKind.KNOWLEDGE, section: String = "") =
        EvidencePackage.Companion.Raw(title, section, text, key, kind)
    private val portugal = EvidencePackage.of(listOf(raw("Portogallo", "Il Portogallo è uno Stato dell'Europa. La capitale è Lisbona. Il Portogallo ha circa 10,3 milioni di abitanti.", "0")))

    // P0-A — a limit formula never makes a following proposition immune
    @Test fun falsePropositionHiddenInAnUncertaintyClauseIsNotPublished() {
        for (cand in listOf(
            "La capitale è Lisbona. Non so con certezza, ma il Portogallo ha 50 milioni di abitanti.",
            "La capitale è Lisbona. Non posso verificare, il Portogallo ha 50 milioni di abitanti.",
            "La capitale è Lisbona. Non ho trovato altro, però il Portogallo ha 50 milioni di abitanti.",
            "La capitale è Lisbona. Secondo le fonti non c'è nulla di più; il Portogallo ha 50 milioni di abitanti.",
        )) {
            val pub = PublicationPolicy.decide("Capitale e abitanti del Portogallo?", cand, portugal)
            assertFalse("50 milioni" in pub.text, "$cand -> ${pub.text}")
            assertTrue(pub.mode != PublicationMode.VERIFIED, "$cand -> ${pub.mode}")
            assertTrue("Lisbona" in pub.text)
        }
    }

    @Test fun aLegitimateLimitDeclarationStaysUsable() {
        val pub = PublicationPolicy.decide("Capitale del Portogallo?", "La capitale è Lisbona. Non ho altre informazioni su questo.", portugal)
        assertEquals(PublicationMode.VERIFIED, pub.mode)
        assertTrue("Non ho altre informazioni" in pub.text)
        val onlyLimit = PublicationPolicy.decide("Capitale del Portogallo?", "Non so rispondere con certezza.", portugal)
        assertTrue(onlyLimit.mode != PublicationMode.VERIFIED) // nothing supported -> never "verified"
    }

    @Test fun aLimitFormulaWithANumberInTheSameClauseIsAProposition() {
        val c = ClaimVerifier.verify("Abitanti?", "Non so, forse sono 50 milioni.", portugal).claims
        assertTrue(c.any { it.status == ClaimStatus.UNVERIFIED || it.status == ClaimStatus.CONTRADICTED }, c.toString())
    }

    @Test fun negationsAndConditionsSurviveInTheVerifiedText() {
        val ev = EvidencePackage.of(listOf(raw("Olio", "L'olio non va sostituito se il motore è nuovo.", "0")))
        val pub = PublicationPolicy.decide("Quando non va sostituito l'olio?", "L'olio non va sostituito se il motore è nuovo.", ev)
        assertEquals(PublicationMode.VERIFIED, pub.mode); assertTrue("non va sostituito se il motore è nuovo" in pub.text)
    }

    // P0-B — identity of citations: same title, different passages
    @Test fun samePassageTitleDifferentContentKeepsDistinctCitations() {
        val ev = EvidencePackage.of(listOf(
            raw("Manuale.pdf", "Il serbatoio contiene 13 litri.", "0", EvidenceKind.DOCUMENT, "edizione 2019"),
            raw("Manuale.pdf", "La batteria dura 8 ore.", "1", EvidenceKind.DOCUMENT, "edizione 2023"),
        ))
        val pub = PublicationPolicy.decide("Quanti litri e quante ore?", "Il serbatoio contiene 13 litri. La batteria dura 8 ore.", ev)
        assertEquals(PublicationMode.VERIFIED, pub.mode)
        assertEquals(2, pub.sources.size, "two passages with one title are two sources")
        assertEquals(listOf("E1", "E2"), pub.sources.map { it.id })
        assertTrue(Regex("13 litri \\[1].*8 ore \\[2]").containsMatchIn(pub.text), pub.text)
    }

    @Test fun incompatibleSameTitlePassagesPointOnlyToTheOneThatSupports() {
        val ev = EvidencePackage.of(listOf(
            raw("Scheda", "Il peso è di 300 grammi.", "0"),
            raw("Scheda", "Il peso è di 450 grammi.", "1"),
        ))
        val pub = PublicationPolicy.decide("Quanto pesa?", "Il peso è di 450 grammi.", ev)
        assertEquals(listOf("E2"), pub.sources.map { it.id }, pub.text)
        assertTrue(pub.text.contains("[1]")) // numbered by order of citation, mapped to E2
    }

    @Test fun relevantButInsufficientPassageIsNotListedAsSupport() {
        val ev = EvidencePackage.of(listOf(raw("Portogallo", "La capitale è Lisbona.", "0"), raw("Spagna", "Madrid ospita il governo.", "1")))
        val pub = PublicationPolicy.decide("Capitale del Portogallo?", "La capitale è Lisbona.", ev)
        assertEquals(listOf("Portogallo"), pub.sources.map { it.title })
    }

    @Test fun markersChosenByTheModelAreIgnored() {
        val ev = EvidencePackage.of(listOf(raw("A", "Il peso è di 300 grammi.", "0"), raw("B", "Il peso è di 450 grammi.", "1")))
        val pub = PublicationPolicy.decide("Quanto pesa?", "Il peso è di 450 grammi [E1] [1].", ev)
        assertEquals(listOf("B"), pub.sources.map { it.title })
    }

    // P0-C — extracts are never cut mid-sentence
    @Test fun extractNeverPublishesATruncatedSentence() {
        val long = "Il componente funziona correttamente " + "con ogni tipo di carico applicato " .repeat(14) + "tranne quando la temperatura supera i 40 gradi, nel qual caso non va usato."
        assertTrue(long.indexOf("tranne") > 320)
        val ev = EvidencePackage.of(listOf(raw("Scheda.pdf", long, "0", EvidenceKind.DOCUMENT)), 2000)
        val pub = PublicationPolicy.decide("Riassumi la scheda.", "Il componente è indistruttibile.", ev, PublicationKind.SUMMARY)
        assertFalse("funziona correttamente con ogni" in pub.text && "tranne" !in pub.text, "a prefix of the sentence was published: ${pub.text}")
        assertTrue("troppo lunghe" in pub.text || "tranne quando" in pub.text)
        assertFalse(pub.text.contains("indistruttibile"))
    }

    @Test fun aShortLeadSentenceIsStillUsedWhole() {
        val ev = EvidencePackage.of(listOf(raw("S.pdf", "Non usare con temperature sopra i 40 gradi. " + "Altro testo. ".repeat(60), "0", EvidenceKind.DOCUMENT)), 2000)
        val pub = PublicationPolicy.decide("Riassumi.", "Si può usare sempre.", ev, PublicationKind.SUMMARY)
        assertTrue("Non usare con temperature sopra i 40 gradi" in pub.text)
    }

    // P0-D — facts without sources
    @Test fun preciseFactsWithoutSourcesAreNotPresentedAsVerified() {
        val out = AnswerFraming.frame(RequestKind.FACTUAL, RetrievalState.ABSENT, "La Tour Eiffel è alta 330 metri.", "Quanto è alta la Tour Eiffel?")
        assertTrue(out.startsWith(AnswerFraming.NO_SOURCES))
        val general = AnswerFraming.frame(RequestKind.FACTUAL, RetrievalState.ABSENT, "Le piante producono energia attraverso la fotosintesi clorofilliana.", "Come producono energia le piante?")
        assertEquals("Le piante producono energia attraverso la fotosintesi clorofilliana.", general)
    }

    @Test fun creativeTransformAndHypotheticalAreNeverQualified() {
        for (k in listOf(RequestKind.CONVERSATION, RequestKind.TRANSFORM, RequestKind.HYPOTHETICAL, RequestKind.CALCULATION))
            assertEquals("Costa 12 euro.", AnswerFraming.frame(k, RetrievalState.ABSENT, "Costa 12 euro.", "?"))
    }

    @Test fun factualRequestsWithoutRecognisedFormulasAreNotSmallTalk() {
        assertEquals(RequestKind.FACTUAL, RequestClassifier.classify("Parlami della Rivoluzione francese"))
        assertEquals(RequestKind.FACTUAL, RequestClassifier.classify("Elenca i pianeti del Sistema Solare"))
        assertEquals(RequestKind.FACTUAL, RequestClassifier.classify("Altezza del Monte Bianco"))
        assertEquals(RequestKind.CONVERSATION, RequestClassifier.classify("grazie mille"))
        assertEquals(RequestKind.CONVERSATION, RequestClassifier.classify("mi sento un po' stanco oggi"))
    }

    // tool receipts: restatement may not add data
    @Test fun toolRestatementWithInventedNumberFallsBackToTheReceipt() {
        val receipt = EvidencePackage.of(listOf(raw("Risultati del sistema", "Batteria: 41%.", "t0", EvidenceKind.TOOL)))
        val ok = PublicationPolicy.decideToolOnly("Quanta batteria ho?", "Hai il 41% di batteria.", receipt)
        assertEquals(PublicationMode.VERIFIED, ok.mode)
        val bad = PublicationPolicy.decideToolOnly("Quanta batteria ho?", "Hai il 41% di batteria e restano 3 ore.", receipt)
        assertEquals(PublicationMode.FAITHFUL_EXTRACT, bad.mode); assertEquals("Batteria: 41%.", bad.text)
        val chat = PublicationPolicy.decideToolOnly("Timer?", "Fatto, ho impostato il timer.", receipt)
        assertEquals(PublicationMode.VERIFIED, chat.mode) // no new data: untouched
    }

    // false refusals: correct answers stay
    @Test fun correctSupportedParaphrasingWithSameKeyWordsIsPublished() {
        val ev = EvidencePackage.of(listOf(raw("Rame", "Il rame fonde a 1.085 °C. È un ottimo conduttore elettrico.", "0")))
        assertEquals(PublicationMode.VERIFIED, PublicationPolicy.decide("Quando fonde il rame?", "Il rame fonde a 1.085 °C, è un ottimo conduttore elettrico.", ev).mode)
    }

    @Test fun summaryKeepsSupportedPartsAndDropsUnsupportedOnes() {
        val ev = EvidencePackage.of(listOf(
            raw("A.pdf", "La catena va lubrificata ogni 500 km.", "0", EvidenceKind.DOCUMENT),
            raw("B.pdf", "Il filtro si sostituisce ogni 10.000 km.", "1", EvidenceKind.DOCUMENT),
        ))
        val pub = PublicationPolicy.decide("Confronta A e B.", "La catena va lubrificata ogni 500 km. Il motore è garantito dieci anni.", ev, PublicationKind.COMPARE)
        assertFalse("garantito" in pub.text); assertTrue("500 km" in pub.text && "10.000 km" in pub.text)
    }
}
