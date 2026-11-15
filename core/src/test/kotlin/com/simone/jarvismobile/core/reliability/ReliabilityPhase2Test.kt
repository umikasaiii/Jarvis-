package com.simone.jarvismobile.core.reliability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regressions for the audit findings B–H of the publication gate (synthetic fixtures; nothing is hardcoded in the product). */
class ReliabilityPhase2Test {
    private fun raw(title: String, text: String, key: String = "k", kind: EvidenceKind = EvidenceKind.KNOWLEDGE) = EvidencePackage.Companion.Raw(title, "", text, key, kind)
    private fun pkg(vararg r: EvidencePackage.Companion.Raw, max: Int = 900) = EvidencePackage.of(r.toList(), max)
    private val portugal = pkg(raw("Portogallo", "Il Portogallo è uno Stato dell'Europa. La capitale è Lisbona. Il Portogallo ha circa 10,3 milioni di abitanti."))

    // C — "secondo le fonti" does not make the proposition non-factual
    @Test fun attributionPhraseDoesNotLaunderAFalseSecondClaim() {
        val pub = PublicationPolicy.decide("Capitale e abitanti del Portogallo?", "La capitale è Lisbona. Secondo le fonti, il Portogallo ha 50 milioni di abitanti.", portugal)
        assertEquals(PublicationMode.ALLOWED_PARTS, pub.mode)
        assertTrue("Lisbona" in pub.text); assertFalse("50 milioni" in pub.text)
    }

    @Test fun attributedTrueClaimStillPasses() {
        val pub = PublicationPolicy.decide("Qual è la capitale del Portogallo?", "Secondo le fonti la capitale è Lisbona.", portugal)
        assertEquals(PublicationMode.VERIFIED, pub.mode)
    }

    // B — no promotion UNVERIFIED -> DERIVED for summaries: an unsupported claim is replaced by verbatim extracts, never approved
    @Test fun summaryNeverApprovesUnsupportedContent() {
        val doc = pkg(raw("Manuale.pdf, pag. 3", "La catena va lubrificata ogni 500 km. Il filtro dell'olio si sostituisce ogni 10.000 km.", "0", EvidenceKind.DOCUMENT))
        val pub = PublicationPolicy.decide("Riassumi il documento.", "La catena va lubrificata ogni 500 km. Il motore ha una garanzia di cinque anni.", doc, PublicationKind.SUMMARY)
        assertFalse("garanzia" in pub.text, pub.text)
        assertTrue("500 km" in pub.text)
        assertTrue(pub.mode == PublicationMode.ALLOWED_PARTS)
        println("DBG " + pub.mode + " | " + pub.text + " | " + pub.report.claims)
    }

    @Test fun unsupportedSummaryFallsBackToExtractsWithCoverage() {
        val doc = pkg(raw("A.pdf", "Il documento A parla di freni a disco.", "0", EvidenceKind.DOCUMENT), raw("B.pdf", "Il documento B parla di pneumatici invernali.", "1", EvidenceKind.DOCUMENT))
        val pub = PublicationPolicy.decide("Confronta A e B.", "Entrambi trattano di motori elettrici di ultima generazione.", doc, PublicationKind.COMPARE)
        assertEquals(PublicationMode.FAITHFUL_EXTRACT, pub.mode)
        assertTrue("freni a disco" in pub.text && "pneumatici invernali" in pub.text)
        assertFalse("elettrici" in pub.text)
    }

    // E — an existing id attached to the WRONG source: markers are discarded and rebuilt from the verified link
    @Test fun modelMarkersAreRebuiltFromVerifiedLinks() {
        val p = pkg(raw("Italia", "Roma è la capitale d'Italia.", "0"), raw("Francia", "Parigi è la capitale della Francia.", "1"))
        val pub = PublicationPolicy.decide("Capitale d'Italia?", "Roma è la capitale d'Italia [E2] [2].", p)
        assertEquals(PublicationMode.VERIFIED, pub.mode)
        assertEquals(listOf("Italia"), pub.sources.map { it.title })
        assertTrue("[1]" in pub.text); assertFalse("[2]" in pub.text); assertFalse("E2" in pub.text)
    }

    @Test fun inventedFileAndPageReferencesAreRejected() {
        val doc = pkg(raw("Manuale.pdf, pag. 8", "Il serbatoio contiene 13 litri.", "0", EvidenceKind.DOCUMENT))
        val ok = PublicationPolicy.decide("Quanti litri?", "Il serbatoio contiene 13 litri (Manuale.pdf, pag. 8).", doc)
        assertEquals(PublicationMode.VERIFIED, ok.mode)
        val bad = PublicationPolicy.decide("Quanti litri?", "Il serbatoio contiene 13 litri come scritto in Altro.pdf a pagina 99.", doc)
        assertTrue(bad.mode != PublicationMode.VERIFIED, bad.text)
        assertFalse("Altro.pdf" in bad.text)
    }

    // D — what the prompt does not contain cannot support the answer
    @Test fun textBeyondTheCutIsNotEvidence() {
        val long = "La batteria dura 8 ore. " + "Ulteriori dettagli tecnici sono riportati in appendice. ".repeat(20) + "Il caricabatterie pesa 300 grammi."
        val p = pkg(raw("Scheda", long), max = 120)
        assertTrue(p.items.single().text.length <= 120)
        assertFalse("300 grammi" in p.render())
        val pub = PublicationPolicy.decide("Quanto pesa il caricabatterie?", "Il caricabatterie pesa 300 grammi.", p)
        assertTrue(pub.mode != PublicationMode.VERIFIED && pub.mode != PublicationMode.ALLOWED_PARTS, pub.text)
        assertFalse("300 grammi" in pub.text.replace("Dalle fonti:", ""))
    }

    @Test fun aFirstSentenceThatDoesNotFitIsOmittedNotCutIntoAFalseComplete() {
        val p = pkg(raw("X", "Questa frase molto lunga contiene una negazione decisiva soltanto alla fine e non può essere dimezzata senza cambiare il significato non è vero."), max = 50)
        assertTrue(p.items.isEmpty()); assertEquals(1, p.omitted.size)
        assertTrue("non inclusi" in EvidencePackage.of(listOf(raw("X", "Una frase troppo lunga per il limite imposto qui.")), 10).render().ifBlank { "non inclusi" })
    }

    @Test fun longToolResultKeepsOnlyWhatFitsAndSaysWhatDidNot() {
        val lines = listOf("Batteria: 41%.", "Timer impostato: 10 minuti.", "Volume: 7.", "Torcia: spenta.")
        val p = EvidencePackage.of(lines.mapIndexed { i, l -> raw("Risultati del sistema", l, "t$i", EvidenceKind.TOOL) }, 900, totalChars = 45)
        assertTrue(p.items.size < lines.size && p.omitted.isNotEmpty())
        val inPrompt = p.items.first().text
        assertTrue("41%" in inPrompt)
        val pub = PublicationPolicy.decide("Com'è la torcia?", "La torcia è spenta.", p)
        assertTrue(pub.mode != PublicationMode.VERIFIED)
    }

    // G — signs, units, attribution
    @Test fun negativeAndPositiveTemperaturesDiffer() {
        val q = Quantities.extract("Minima di -5 °C e massima di +5 °C. Dal 2020-2021 con −3,5 °C.")
        assertEquals(listOf("-5", "5", "2020", "2021", "-3.5"), q.map { it.value.stripTrailingZeros().toPlainString() })
        assertEquals(Quantities.Match.DIFFERENT, Quantities.compare(q[0], q[1]))
        val ev = pkg(raw("Meteo", "La temperatura minima è di -5 °C."))
        val bad = ClaimVerifier.verify("Minima?", "La temperatura minima è di 5 °C.", ev).claims.single()
        assertEquals(ClaimStatus.CONTRADICTED, bad.status)
        val ok = ClaimVerifier.verify("Minima?", "La temperatura minima è di −5 °C.", ev).claims.single()
        assertEquals(ClaimStatus.EXPLICITLY_SUPPORTED, ok.status)
    }

    @Test fun compositeUnitsAreConvertedOnlyThroughTheTable() {
        val ev = pkg(raw("Auto", "La velocità massima è di 180 km/h."))
        assertEquals(ClaimStatus.EXPLICITLY_SUPPORTED, ClaimVerifier.verify("Velocità?", "La velocità massima è di 180 km/h.", ev).claims.single().status)
        assertEquals(ClaimStatus.CONTRADICTED, ClaimVerifier.verify("Velocità?", "La velocità massima è di 180 m/s.", ev).claims.single().status)
        assertEquals(ClaimStatus.DERIVED, ClaimVerifier.verify("Velocità?", "La velocità massima è di 50 m/s.", ev).claims.single().status)
    }

    @Test fun aQuantityRepeatedFromTheQuestionIsStillChecked() {
        val ev = pkg(raw("Everest", "L'Everest è alto 8.849 metri."))
        val pub = PublicationPolicy.decide("È vero che l'Everest è alto 9.000 metri?", "Sì, l'Everest è alto 9.000 metri.", ev)
        assertTrue(pub.mode != PublicationMode.VERIFIED, pub.text)
    }

    @Test fun qualifiersAndUniversalsMustSurvive() {
        val ev = pkg(raw("Olio", "L'olio va sostituito circa ogni 10.000 km, solo con l'uso normale."))
        assertTrue(ClaimVerifier.verify("Ogni quanto?", "L'olio va sostituito ogni 10.000 km.", ev).claims.single().status == ClaimStatus.UNVERIFIED)
        assertEquals(ClaimStatus.UNVERIFIED, ClaimVerifier.verify("Ogni quanto?", "L'olio va sempre sostituito circa ogni 10.000 km, solo con l'uso normale.", ev).claims.single().status)
    }

    // not universal abstention
    @Test fun correctParaphraseOfExactWordsAndShortAnswersStillPass() {
        val ev = pkg(raw("Rame", "Il rame fonde a 1.085 °C. È un ottimo conduttore elettrico."))
        assertEquals(PublicationMode.VERIFIED, PublicationPolicy.decide("A che temperatura fonde il rame?", "Il rame fonde a 1.085 °C.", ev).mode)
        assertEquals(PublicationMode.VERIFIED, PublicationPolicy.decide("Il rame conduce elettricità?", "È un ottimo conduttore elettrico.", ev).mode)
    }

    @Test fun requestKindsNeedEvidenceOnlyWhenFactual() {
        assertEquals(RequestKind.CONVERSATION, RequestClassifier.classify("Ciao, come stai?"))
        assertEquals(RequestKind.TRANSFORM, RequestClassifier.classify("Traduci in inglese: buongiorno a tutti"))
        assertEquals(RequestKind.CALCULATION, RequestClassifier.classify("Quanto fa 12 * 7?"))
        assertEquals(RequestKind.HYPOTHETICAL, RequestClassifier.classify("Se avessi un milione di euro cosa farei?"))
        assertEquals(RequestKind.FACTUAL, RequestClassifier.classify("Qual è la capitale del Portogallo?"))
        assertTrue(RequestClassifier.needsEvidence(RequestKind.FACTUAL)); assertFalse(RequestClassifier.needsEvidence(RequestKind.HYPOTHETICAL))
    }

    @Test fun revisionNoteNamesTheRejectedClaimAndReason() {
        val pub = PublicationPolicy.decide("Capitale e abitanti?", "La capitale è Lisbona. Gli abitanti sono 50 milioni.", portugal)
        val note = PublicationPolicy.revisionNote(pub)!!
        assertTrue("50 milioni" in note && "valore diverso" in note)
    }

    @Test fun footerLinesNeverReachTheSeedHistory() {
        val text = "Lisbona [1].\nFonti a supporto:\n[1] Portogallo\nFonti consultate:\n[1] A.pdf\n[2] B.pdf"
        assertEquals("Lisbona [1].", com.simone.jarvismobile.core.quality.AnswerQuality.stripPresentationNotes(text))
    }
}
