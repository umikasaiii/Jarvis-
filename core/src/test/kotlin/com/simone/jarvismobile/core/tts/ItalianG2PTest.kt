package com.simone.jarvismobile.core.tts

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class ItalianG2PTest {

    private fun w(s: String) = ItalianG2P.word(s)

    @Test
    fun `soft and hard c`() {
        assertEquals("ˈtʃao", w("ciao"))
        assertEquals("ˈkaza", w("casa"))
        assertEquals("ˈkjave", w("chiave"))
        assertEquals("ˈtʃɛna", w("cèna"))
    }

    @Test
    fun `soft and hard g`() {
        assertEquals("ˈdʒɛlo", w("gèlo"))
        assertEquals("ˈɡatto", w("gatto"))
        assertEquals("ˈɡiro", w("ghiro"))
    }

    @Test
    fun `gn and gl are single sounds`() {
        assertEquals("ˈɲɔkko", w("gnòcco"))
        assertEquals("ˈfiʎo", w("figlio"))
        // "gli" on its own keeps its vowel.
        assertEquals("ʎi", w("gli"))
    }

    @Test
    fun `sc softens before front vowels only`() {
        assertEquals("ˈʃɛna", w("scèna"))
        assertEquals("ˈskala", w("scala"))
        assertEquals("ˈskɛrtso", w("schèrzo"))
    }

    @Test
    fun `s voices between vowels`() {
        // rosa -> /ˈrɔza/ (voiced), sole -> /ˈsole/ (not).
        assertTrue(w("rosa").contains("z"))
        assertTrue(w("sole").startsWith("ˈs"))
        // Before a voiced consonant it also voices: sbaglio.
        assertTrue(w("sbaglio").contains("zb"), w("sbaglio"))
    }

    @Test
    fun `z is an affricate and doubles as a geminate`() {
        assertEquals("ˈtsio", w("zio"))
        assertEquals("ˈpittsa", w("pizza"))
    }

    @Test
    fun `qu is a labialised k`() {
        assertEquals("ˈkwando", w("quando"))
    }

    @Test
    fun `h is silent`() {
        assertEquals("ˈanno", w("hanno"))
    }

    @Test
    fun `stress falls on the penultimate syllable by default`() {
        assertEquals("aˈmiko", w("amico"))
        assertEquals("siˈmone", w("Simone"))
        // "tavolo" is really tà-vo-lo. Antepenultimate stress is lexical, so the
        // penultimate default gets it wrong — recorded here rather than hidden.
        assertEquals("taˈvolo", w("tavolo"))
    }

    @Test
    fun `a written accent moves the stress and opens the vowel`() {
        assertEquals("perˈke", w("perché"))
        // A geminate splits across the boundary: caf-fè, so the mark sits
        // between the two consonants.
        assertEquals("kafˈfɛ", w("caffè"))
        // The accent must survive even when it is the last letter.
        assertTrue(w("città").endsWith("ˈta"))
    }

    @Test
    fun `monosyllables are left unstressed`() {
        assertEquals("il", w("il"))
        assertEquals("kon", w("con"))
    }

    @Test
    fun `a sentence keeps its punctuation and word boundaries`() {
        val p = ItalianG2P.phonemize("Ciao, come stai?")
        assertTrue(p.startsWith("ˈtʃao,"), p)
        assertTrue(p.endsWith("?"), p)
        assertTrue(p.contains(' '), p)
    }

    @Test
    fun `digits are spelled before phonemisation`() {
        val p = ItalianG2P.phonemize("68%")
        // "sessantotto" — no digit may survive into the phoneme string.
        assertTrue(p.none { it.isDigit() }, p)
        assertTrue(p.contains("sessan"), p)
    }

    /**
     * The guard that matters most: whatever the transducer emits has to exist in
     * the model's symbol table, or those phonemes are silently dropped and the
     * word is mispronounced with no visible error anywhere.
     */
    @Test
    fun `every phoneme produced is encodable by the built-in vocabulary`() {
        val vocab = PhonemeVocabulary.BUILT_IN
        val sample = listOf(
            "Ciao Simone, come stai oggi?",
            "Alle 15:30 hai la revisione dell'auto e la batteria è al 68 per cento.",
            "Perché non gli chiedi di sbrigarsi? Gnocchi, sciarpa, quaglia, pizza, azzurro.",
            "Vorrei aggiungere un promemoria per venerdì alle nove e mezza.",
            "Il figlio dello scienziato guarda lo schermo con gli occhiali.",
        )
        val missing = sortedSetOf<Char>()
        sample.forEach { line ->
            ItalianG2P.phonemize(line).forEach { c -> if (!vocab.contains(c)) missing.add(c) }
        }
        assertEquals(0, missing.size, "phonemes outside the model vocabulary: $missing")
    }
}
