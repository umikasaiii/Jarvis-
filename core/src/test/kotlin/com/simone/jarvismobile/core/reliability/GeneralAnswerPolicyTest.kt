package com.simone.jarvismobile.core.reliability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hotfix post-M4 / H3. A general-knowledge answer keeps the model's words verbatim and is introduced by a label the PIPELINE adds; the label is never a guarantee. */
class GeneralAnswerPolicyTest {
    @Test fun theFiveClassesAreDistinguished() {
        val g = GeneralAnswerPolicy.AnswerClass.values().toSet()
        assertEquals(5, g.size)
        assertEquals(GeneralAnswerPolicy.AnswerClass.SOURCE_SUPPORTED, GeneralAnswerPolicy.classify(PublicationMode.VERIFIED, setOf(EvidenceKind.KNOWLEDGE)))
        assertEquals(GeneralAnswerPolicy.AnswerClass.SOURCE_SUPPORTED, GeneralAnswerPolicy.classify(PublicationMode.FAITHFUL_EXTRACT, setOf(EvidenceKind.DOCUMENT, EvidenceKind.MEMORY)))
        assertEquals(GeneralAnswerPolicy.AnswerClass.PERSONAL_MEMORY, GeneralAnswerPolicy.classify(PublicationMode.VERIFIED, setOf(EvidenceKind.MEMORY)))
        assertEquals(GeneralAnswerPolicy.AnswerClass.DETERMINISTIC_TOOL, GeneralAnswerPolicy.classify(PublicationMode.VERIFIED, setOf(EvidenceKind.TOOL)))
        assertEquals(GeneralAnswerPolicy.AnswerClass.GENERAL_UNVERIFIED, GeneralAnswerPolicy.classify(null, emptySet()))
        assertEquals(GeneralAnswerPolicy.AnswerClass.ABSTENTION, GeneralAnswerPolicy.classify(PublicationMode.ABSTAIN, setOf(EvidenceKind.KNOWLEDGE)))
    }

    @Test fun anExplicitRequestForGeneralKnowledgeIsRecognisedAndOrdinaryQuestionsAreNot() {
        listOf(
            "Senza usare gli archivi offline come prova, spiegami usando le tue conoscenze generali quale organo produce l'insulina. Specifica che non è verificata.",
            "Rispondi con la tua conoscenza generale: chi scrisse i Promessi Sposi?",
            "Senza consultare le fonti, dimmi quando fu fondata Roma",
        ).forEach { assertTrue(GeneralAnswerPolicy.requestsGeneralOnly(it), it) }
        listOf("Qual è la capitale del Portogallo?", "Cerca negli archivi cos'è un archivio storico", "Spiegami cos'è una variabile in Python").forEach { assertFalse(GeneralAnswerPolicy.requestsGeneralOnly(it), it) }
    }

    @Test fun aGeneralAnswerKeepsTheModelsWordsVerbatimAndCarriesTheLabelFirst() {
        val answers = listOf("L'insulina è prodotta principalmente dal fegato.", "La tiroide si trova nel ginocchio.", "Il paracetamolo si assume a dosi illimitate.")
        val questions = listOf("Quale organo produce l'insulina?", "Dove si trova la tiroide?", "Come si assume il paracetamolo?")
        for ((q, a) in questions.zip(answers)) {
            val published = AnswerFraming.frame(RequestKind.FACTUAL, RetrievalState.USER_REQUESTED_GENERAL, a, q)
            assertTrue(published.endsWith(a), "the pipeline changed the model's sentence: $published")
            assertTrue(published.startsWith(GeneralAnswerPolicy.USER_REQUESTED_GENERAL), published)
            assertTrue(!published.contains("[1]") && !published.contains("Fonti a supporto"), "a general answer must carry no citation: $published")
        }
    }

    @Test fun aHighRiskAnswerWithoutSourcesAlwaysStatesItsLimitEvenWithoutANumberOrAName() {
        for (state in listOf(RetrievalState.ABSENT, RetrievalState.UNAVAILABLE, RetrievalState.NO_RESULT)) {
            val p = AnswerFraming.frame(RequestKind.FACTUAL, state, "L'insulina è prodotta dal fegato.", "Quale organo produce l'insulina?")
            assertTrue(p.contains("non è verificato") && p.contains(GeneralAnswerPolicy.HIGH_RISK_NOTE), "$state -> $p")
        }
        // an ordinary subject keeps the previous behaviour: no blanket disclaimer on a general explanation without precise facts
        assertEquals("Il cielo è azzurro per la diffusione della luce.", AnswerFraming.frame(RequestKind.FACTUAL, RetrievalState.NO_RESULT, "Il cielo è azzurro per la diffusione della luce.", "Perché il cielo è azzurro?"))
    }

    @Test fun highRiskDetectionIsASmallClosedLexiconThatOnlyLabels() {
        listOf("Qual è la dose massima di ibuprofene?", "Che sintomi ha il diabete?", "Come funziona un mutuo a tasso fisso?").forEach { assertTrue(GeneralAnswerPolicy.isHighRisk(it), it) }
        listOf("Qual è la capitale del Portogallo?", "Spiegami cos'è una variabile in Python", "Chi ha dipinto la Gioconda?").forEach { assertFalse(GeneralAnswerPolicy.isHighRisk(it), it) }
    }

    @Test fun modelsWithUnrelatedKindsOfRequestAreNeverLabelled() {
        assertEquals("ok", AnswerFraming.frame(RequestKind.CONVERSATION, RetrievalState.USER_REQUESTED_GENERAL, "ok", "ciao"))
    }
}
