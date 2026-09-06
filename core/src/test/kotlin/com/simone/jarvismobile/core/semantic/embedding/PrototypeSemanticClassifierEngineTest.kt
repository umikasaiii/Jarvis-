package com.simone.jarvismobile.core.semantic.embedding

import com.simone.jarvismobile.core.semantic.ReferenceMode
import com.simone.jarvismobile.core.semantic.SemanticFrame
import com.simone.jarvismobile.core.semantic.SemanticIntent
import com.simone.jarvismobile.core.semantic.SemanticOperation
import com.simone.jarvismobile.core.semantic.SemanticSlot
import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § FASE 2A.11 §19 — invariant tests A-H, not case-by-case fixes. The engine
 * under test is built ONLY from the corpus's `train`-split prototypes
 * ([TestCorpusFixture.buildEngine]); every sentence classified here comes
 * from the `validation`/`test` splits (genuinely held out) or is new text
 * altogether never seen anywhere in the corpus — proving generalization, not
 * memorization, exactly as §12/§19A require ("Il TEST SET deve includere
 * formulazioni NON presenti nei prototipi").
 */
class PrototypeSemanticClassifierEngineTest {

    private val engine = TestCorpusFixture.buildEngine()

    private fun classify(text: String, previous: SemanticFrame? = null) =
        engine.classify(FakeSemanticEmbedder.embed(text), previous)

    // --- A. Unseen paraphrase ------------------------------------------------

    @Test
    fun `A - held-out WEATHER paraphrases never in the training split still classify as WEATHER`() {
        // These exact sentences are held out (split=validation/test) — never used to build centroids.
        listOf(
            "Che tempo fara tra una settimana circa",
            "Sara nuvoloso o sereno questo pomeriggio",
            "Domani si suda?",
        ).forEach { text ->
            val result = classify(text)
            assertFalse(result.ood, "expected in-distribution for: $text")
            assertEquals(SemanticIntent.CAPABILITY_QUERY, result.frame?.intent, "for: $text")
            assertEquals(setOf(ToolFamily.WEATHER), result.frame?.domains, "for: $text")
        }
    }

    @Test
    fun `A - a brand new HEALTH sentence, not in the corpus at all, still classifies as HEALTH`() {
        val result = classify("Ho dormito poco o tanto stanotte secondo i dati?")
        assertFalse(result.ood)
        assertEquals(setOf(ToolFamily.HEALTH), result.frame?.domains)
    }

    @Test
    fun `A - a brand new KNOWLEDGE sentence, never seen, still classifies as KNOWLEDGE_QUERY`() {
        val result = classify("Spiegami perche gli aerei riescono a volare nonostante il peso")
        assertFalse(result.ood)
        assertEquals(SemanticIntent.KNOWLEDGE_QUERY, result.frame?.intent)
        assertEquals(setOf(ToolFamily.KNOWLEDGE), result.frame?.domains)
    }

    // --- B. Cross-domain contamination ---------------------------------------

    @Test
    fun `B - the classifier decision for an explicit WEATHER turn is identical whether or not a HEALTH previous frame is passed`() {
        val healthPrevious = SemanticFrame(
            intent = SemanticIntent.CAPABILITY_QUERY, domains = setOf(ToolFamily.HEALTH), operation = SemanticOperation.GET,
            temporalExpression = null, metric = null, aggregation = null, entities = emptyList(),
            referenceMode = ReferenceMode.NONE, requiresGrounding = true, confidence = 0.9,
            explicitSlots = setOf(SemanticSlot.DOMAINS),
        )
        val withoutPrevious = classify("Domani farà caldo?", previous = null)
        val withHealthPrevious = classify("Domani farà caldo?", previous = healthPrevious)
        assertEquals(setOf(ToolFamily.WEATHER), withoutPrevious.frame?.domains)
        assertEquals(setOf(ToolFamily.WEATHER), withHealthPrevious.frame?.domains)
        assertFalse(ToolFamily.HEALTH in (withHealthPrevious.frame?.domains ?: emptySet()))
    }

    @Test
    fun `B - WEATHER never contaminates a following explicit AGENDA turn`() {
        classify("Piovera nel weekend?")
        val result = classify("Che impegni ho domani?")
        assertEquals(setOf(ToolFamily.AGENDA), result.frame?.domains)
        assertFalse(ToolFamily.WEATHER in (result.frame?.domains ?: emptySet()))
    }

    @Test
    fun `B - AGENDA never contaminates a following explicit DEVICE_INFO turn`() {
        classify("Cosa devo fare oggi pomeriggio?")
        val result = classify("Quanta RAM ha questo telefono?")
        assertEquals(setOf(ToolFamily.DEVICE_INFO), result.frame?.domains)
        assertFalse(ToolFamily.AGENDA in (result.frame?.domains ?: emptySet()))
    }

    @Test
    fun `B - KNOWLEDGE never contaminates a following explicit DEVICE_INFO turn, and vice versa`() {
        classify("Che differenza c'e tra RAM e VRAM?")
        val deviceResult = classify("Quanta RAM ha questo telefono?")
        assertEquals(setOf(ToolFamily.DEVICE_INFO), deviceResult.frame?.domains)

        classify("Quanta RAM ha questo telefono?")
        val knowledgeResult = classify("Che differenza c'e tra RAM e VRAM?")
        assertEquals(SemanticIntent.KNOWLEDGE_QUERY, knowledgeResult.frame?.intent)
        assertEquals(setOf(ToolFamily.KNOWLEDGE), knowledgeResult.frame?.domains)
    }

    // --- C. Follow-up ---------------------------------------------------------

    @Test
    fun `C - a bare AGENDA-shaped follow-up classifies as ELLIPSIS with no domain of its own`() {
        val result = classify("E dopodomani?")
        assertFalse(result.ood)
        assertEquals(ReferenceMode.ELLIPSIS, result.frame?.referenceMode)
        assertTrue(result.frame?.domains.isNullOrEmpty())
        assertFalse(SemanticSlot.DOMAINS in (result.frame?.explicitSlots ?: emptySet()))
    }

    @Test
    fun `C - a bare DEVICE_INFO partitive follow-up classifies as PARTITIVE with no domain of its own`() {
        val result = classify("Quanta ne ho ancora disponibile adesso?")
        assertFalse(result.ood)
        assertEquals(ReferenceMode.PARTITIVE, result.frame?.referenceMode)
        assertTrue(result.frame?.domains.isNullOrEmpty())
    }

    @Test
    fun `C - a bare WEATHER-shaped follow-up classifies as ELLIPSIS`() {
        val result = classify("E venerdi?")
        assertFalse(result.ood)
        assertEquals(ReferenceMode.ELLIPSIS, result.frame?.referenceMode)
    }

    // --- D. Multi-source --------------------------------------------------------

    @Test
    fun `D - a held-out HEALTH+AGENDA multi-source sentence keeps both domains, never picks one`() {
        val result = classify("Guardando sia il mio sonno recente sia gli appuntamenti di domani, come dovrei organizzare la serata?")
        assertFalse(result.ood)
        assertEquals(SemanticIntent.MULTI_SOURCE_REASONING, result.frame?.intent)
        assertEquals(setOf(ToolFamily.HEALTH, ToolFamily.AGENDA), result.frame?.domains)
    }

    @Test
    fun `D - a held-out WEATHER+AGENDA multi-source sentence keeps both domains`() {
        val result = classify("Sapendo che domani piove e che ho la riunione presto, meglio uscire con anticipo?")
        assertFalse(result.ood)
        assertEquals(setOf(ToolFamily.WEATHER, ToolFamily.AGENDA), result.frame?.domains)
    }

    @Test
    fun `D - a new HEALTH+WEATHER multi-source sentence, never seen, still keeps both domains`() {
        val result = classify("Considerando il meteo di oggi e quanto ho dormito, conviene rimandare la corsa a domani?")
        assertFalse(result.ood)
        assertEquals(setOf(ToolFamily.WEATHER, ToolFamily.HEALTH), result.frame?.domains)
    }

    // --- E. Hard negatives --------------------------------------------------------

    @Test
    fun `E - a KNOWLEDGE question about RAM never resolves to DEVICE_INFO`() {
        val result = classify("Che cos'e la RAM?")
        assertEquals(SemanticIntent.KNOWLEDGE_QUERY, result.frame?.intent)
        assertEquals(setOf(ToolFamily.KNOWLEDGE), result.frame?.domains)
    }

    @Test
    fun `E - a DEVICE_INFO question about RAM never resolves to KNOWLEDGE_QUERY`() {
        val result = classify("Il mio telefono quanta memoria RAM monta di preciso?")
        assertEquals(SemanticIntent.CAPABILITY_QUERY, result.frame?.intent)
        assertEquals(setOf(ToolFamily.DEVICE_INFO), result.frame?.domains)
    }

    @Test
    fun `E - a WEATHER question about temperature never resolves to HEALTH`() {
        val result = classify("Fa molto freddo fuori con questo vento?")
        assertEquals(setOf(ToolFamily.WEATHER), result.frame?.domains)
    }

    @Test
    fun `E - a HEALTH question about the heartbeat never resolves to WEATHER`() {
        val result = classify("Il mio battito cardiaco e regolare in questi giorni?")
        assertEquals(setOf(ToolFamily.HEALTH), result.frame?.domains)
    }

    @Test
    fun `E - a generic future-oriented life question never resolves to AGENDA`() {
        // A deliberately AGENDA-shaped surface ("domani", a question about the
        // future) with no real planner content — must not be forced into AGENDA.
        val result = classify("Domani sara un giorno migliore di oggi, secondo te?")
        assertFalse(result.frame?.domains?.contains(ToolFamily.AGENDA) ?: false)
    }

    // --- F. OOD / out-of-distribution -----------------------------------------

    @Test
    fun `F - a request entirely outside every known concept is OOD, never forced into a capability`() {
        val result = classify("Xilonqua vetrusmo fantibolo glorx nimparil squetto blenzafra")
        assertTrue(result.ood)
        assertEquals(null, result.frame)
        assertTrue(result.failureReason?.startsWith("ood:") == true)
    }

    @Test
    fun `F - OOD result never carries a domain-bearing frame`() {
        val result = classify("asdkj qwoiuxc zzxpvt nnnn mrrrkk")
        assertTrue(result.ood)
        assertEquals(null, result.frame)
    }

    // --- G. Typo / spoken-form phrasing ---------------------------------------

    @Test
    fun `G - lowercase no-punctuation spoken-style phrasing still classifies correctly`() {
        listOf(
            "quanto ho dormito stanotte in totale" to ToolFamily.HEALTH,
            "che impegni ho segnato per questa settimana" to ToolFamily.AGENDA,
            "quanto spazio libero c e rimasto sul mio telefono" to ToolFamily.DEVICE_INFO,
        ).forEach { (text, expectedDomain) ->
            val result = classify(text)
            assertFalse(result.ood, "expected in-distribution for: $text")
            assertEquals(setOf(expectedDomain), result.frame?.domains, "for: $text")
        }
    }

    // --- H. Soak: >=500 turns, no cross-turn state leak -----------------------

    @Test
    fun `H - 500+ mixed turns classify deterministically with no state carried between calls`() {
        val heldOut = TestCorpusFixture.corpus.prototypes.filter { it.split != "train" }
        require(heldOut.isNotEmpty())
        val script = (0 until 520).map { i -> heldOut[i % heldOut.size] }

        var previous: SemanticFrame? = null
        val firstPassDomains = mutableListOf<Set<ToolFamily>?>()
        for (proto in script) {
            val result = classify(proto.text, previous)
            firstPassDomains += result.frame?.domains
            if (!result.ood) previous = result.frame
        }

        // Re-running the identical script from a fresh `previous = null` state
        // must reproduce the EXACT same sequence of domain decisions — proving
        // no hidden mutable state inside the engine itself (it is stateless by
        // construction: every `classify()` call only reads its own arguments).
        var previous2: SemanticFrame? = null
        val secondPassDomains = mutableListOf<Set<ToolFamily>?>()
        for (proto in script) {
            val result = classify(proto.text, previous2)
            secondPassDomains += result.frame?.domains
            if (!result.ood) previous2 = result.frame
        }

        assertEquals(firstPassDomains, secondPassDomains)
    }

    @Test
    fun `H - across the same 500+ turn soak, no CAPABILITY_QUERY frame ever ends up with a KNOWLEDGE domain`() {
        val heldOut = TestCorpusFixture.corpus.prototypes.filter { it.split != "train" }
        val script = (0 until 520).map { i -> heldOut[i % heldOut.size] }
        for (proto in script) {
            val result = classify(proto.text)
            if (result.frame?.intent == SemanticIntent.CAPABILITY_QUERY || result.frame?.intent == SemanticIntent.MULTI_SOURCE_REASONING) {
                assertFalse(ToolFamily.KNOWLEDGE in (result.frame?.domains ?: emptySet()), "leaked KNOWLEDGE for: ${proto.text}")
            }
        }
    }
}
