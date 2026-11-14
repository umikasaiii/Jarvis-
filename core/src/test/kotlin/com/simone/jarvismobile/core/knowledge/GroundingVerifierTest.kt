package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.quality.AnswerQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KNOWLEDGE ENGINE 2.0 — the four grounding states and the citation rules, over SIMULATED sources and SIMULATED model
 * answers. The "answers" are strings written here, not output of the real model: nothing in this file measures Gemma.
 */
class GroundingVerifierTest {
    private val withEnc = KnowledgeFixtures2.withEncyclopedia()
    private val phone = KnowledgeFixtures2.phoneToday()
    private fun ev(q: String, srcs: List<com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource> = withEnc) =
        EvidenceAssembler().assemble(q, srcs.flatMap { it.articles(q, 3) }, emptyList(), 4)

    @Test fun `CLAIM_SUPPORTED - values in a supporting window, only that source cited`() {
        val q = "Qual è la capitale della Polonia?"
        val e = ev(q)
        val v = GroundingVerifier.verify(q, "La capitale della Polonia è Varsavia.", e)
        assertEquals(GroundingState.CLAIM_SUPPORTED, v.state)
        assertEquals(listOf("Polonia"), v.supporting.map { it.chunk.sourceTitle })
        val footer = SourceFooter.build(v, e, 2026)
        assertTrue(footer.startsWith(SourceFooter.SUPPORTED_PREFIX) && "Enciclopedia" in footer, footer)
    }

    @Test fun `an invented name or number is never confirmed and the source is only consulted`() {
        val q = "Qual è la capitale della Polonia?"
        val e = ev(q)
        val wrong = GroundingVerifier.verify(q, "La capitale della Polonia è Cracovia.", e)
        assertEquals(GroundingState.SOURCE_FOUND, wrong.state)
        assertTrue("Cracovia" in wrong.unconfirmed)
        assertTrue(wrong.supporting.isEmpty(), "an unconfirmed answer must not cite the source as support")
        val footer = SourceFooter.build(wrong, e, 2026)
        assertTrue(footer.startsWith(SourceFooter.CONSULTED_PREFIX) && !footer.startsWith(SourceFooter.SUPPORTED_PREFIX), footer)

        val q2 = "Quanto è lungo il Tamigi?"
        val e2 = ev(q2)
        assertEquals(GroundingState.CLAIM_SUPPORTED, GroundingVerifier.verify(q2, "Il Tamigi è lungo circa 346 km.", e2).state)
        val bad = GroundingVerifier.verify(q2, "Il Tamigi è lungo circa 520 km.", e2)
        assertEquals(GroundingState.SOURCE_FOUND, bad.state); assertTrue("520" in bad.unconfirmed)
    }

    @Test fun `a merely RELATED passage can never make an answer CLAIM_SUPPORTED`() {
        val q = "Quanto è alto l'Everest?" // the source says "altezza": a candidate, not proof
        val e = ev(q) as Evidence.Grounded
        assertTrue(!e.supportEstablished, e.passages.map { it.related }.toString())
        val v = GroundingVerifier.verify(q, "L'Everest è alto 8.849 metri.", e)
        assertEquals(GroundingState.SOURCE_FOUND, v.state)
        assertTrue(v.supporting.isEmpty())
    }

    @Test fun `an answer without values is supported only if it mostly reuses the supporting window's words`() {
        val q = "Che cos'è il diabete?"
        val e = ev(q, phone)
        val ok = GroundingVerifier.verify(q, "Il diabete mellito è una malattia metabolica con elevati livelli di glucosio nel sangue.", e)
        assertEquals(GroundingState.CLAIM_SUPPORTED, ok.state)
        val off = GroundingVerifier.verify(q, "Il diabete è un disturbo causato da un virus che colpisce i polmoni durante l'inverno.", e)
        assertEquals(GroundingState.SOURCE_FOUND, off.state)
    }

    @Test fun `INSUFFICIENT_EVIDENCE and NO_EVIDENCE are distinct, and neither cites a source as support`() {
        val q = "Qual è la capitale del Portogallo?"
        val e = ev(q, phone)
        assertTrue(e is Evidence.Insufficient)
        val v = GroundingVerifier.verify(q, "La capitale del Portogallo è Lisbona.", e)
        assertEquals(GroundingState.INSUFFICIENT_EVIDENCE, v.state)
        assertTrue(v.supporting.isEmpty())
        assertTrue(SourceFooter.build(v, e, 2026).startsWith(SourceFooter.INSUFFICIENT_PREFIX))

        val none = GroundingVerifier.verify("Qual è la capitale della Mongolia?", "Ulan Bator.", Evidence.NoEvidence)
        assertEquals(GroundingState.NO_EVIDENCE, none.state)
        assertEquals("", SourceFooter.build(none, Evidence.NoEvidence, 2026))
        assertEquals(GroundingState.NO_EVIDENCE, GroundingVerifier.verify("x", "y", null).state)
    }

    @Test fun `with two supporting sources only the one holding the answer's value is cited`() {
        val q = "Confronta la Polonia e l'Ungheria per superficie"
        val e = ev(q)
        val v = GroundingVerifier.verify(q, "L'Ungheria ha una superficie di circa 93.030 km².", e)
        assertEquals(GroundingState.CLAIM_SUPPORTED, v.state)
        assertEquals(listOf("Ungheria"), v.supporting.map { it.chunk.sourceTitle })
    }

    @Test fun `presentation notes are stripped before an answer is replayed to the model`() {
        val shown = "La capitale è Roma.\n${AnswerQuality.NOTE_NOT_CONFIRMED}\n${SourceFooter.CONSULTED_PREFIX}Enciclopedia.\n${SourceFooter.INSUFFICIENT_PREFIX}Wikizionario."
        assertEquals("La capitale è Roma.", AnswerQuality.stripPresentationNotes(shown))
    }
}
