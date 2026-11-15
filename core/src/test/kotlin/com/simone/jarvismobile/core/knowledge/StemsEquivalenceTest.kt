package com.simone.jarvismobile.core.knowledge

import java.text.Normalizer
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The single-pass tokenizer / ASCII-fast-path fold against the regex implementations they replaced (KNOWLEDGE ENGINE 2.1). */
class StemsEquivalenceTest {
    private fun referenceFold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    private fun referenceStems(text: String): List<String> =
        KnowledgeSearch.NON_WORD.split(referenceFold(text).replace(Regex("""(?<=\p{L})[-‐‑](?=\p{L})"""), ""))
            .filter { (it.length >= 3 || (it.length == 2 && it.any(Char::isDigit))) && !KnowledgeText.isStopword(it) }
            .map { KnowledgeSearch.stemWord(it) }

    private val alphabet = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -‐‑'’.,;:!?()àèéìòùÀÈÉçñüßÆøåłŁİıǆ" +
        "́̈日本語한국어🙂😀𝐀\n\t_/\\\"«»–—").toList()

    @Test fun `fold keeps the exact behaviour of the normalising implementation`() {
        val rnd = Random(11)
        repeat(3000) {
            val s = buildString { repeat(rnd.nextInt(60)) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
            assertEquals(referenceFold(s), KnowledgeText.fold(s), "fold of [$s]")
        }
    }

    @Test fun `stems are identical to the regex tokenizer on random and real-looking text`() {
        val rnd = Random(5)
        repeat(5000) {
            val s = buildString { repeat(rnd.nextInt(80)) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
            assertEquals(referenceStems(s), SupportJudge.stems(s), "stems of [$s]")
        }
        for (s in listOf(
            "Qual è la capitale del Portogallo?", "Wi-Fi e Bluetooth: come funziona il Wi‑Fi 802.11?", "K2 e A4, l'Everest (8.849 m) è alto; più dell'altezza del Cervino",
            "L'ipertensione arteriosa è l'aumento persistente della pressione del sangue.", "socio-economico, anti-infiammatorio; 12-34 e a-1 e 1-a", "ÀÈÌÒÙ àèìòù città università perché",
            "", " ", "--", "a-b-c", "x--y", "😀 emoji 🙂 tra le parole", "日本語 の 文字",
        )) assertEquals(referenceStems(s), SupportJudge.stems(s), "stems of [$s]")
    }
}
