package com.simone.jarvismobile.core.quality

import com.simone.jarvismobile.core.quality.AnswerQuality.Flag
import com.simone.jarvismobile.core.quality.AnswerQuality.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnswerQualityTest {
    private fun eval(q: String, a: String, earlier: List<String> = emptyList(), earlierQ: List<String> = emptyList(), ev: String? = null) =
        AnswerQuality.evaluate(q, a, earlier, earlierQ, ev)

    // ---- completeness ---------------------------------------------------------------------------------
    @Test fun `answers cut mid sentence are flagged`() {
        listOf(
            "In Italia la lingua ufficiale è l'italiano, ma esistono anche",
            "Un SSD è un tipo di memoria che usa chip invece di dischi e",
            "La fotosintesi è il processo con cui le piante trasformano la luce in",
            "Ci sono diversi motivi:",
            "Il sistema funziona così (",
        ).forEach { assertTrue(Flag.INCOMPLETE in eval("q", it).flags, it) }
    }

    @Test fun `complete answers are not flagged`() {
        listOf(
            "Roma", "Sì.", "L'italiano è la lingua ufficiale, insieme a diverse lingue regionali e minoritarie.",
            "Un elenco: pane, pasta, pizza", "Hai ragione!", "Il risultato è 144", "Circa 7 giorni, cioè una settimana.",
            "Ecco i passi: 1. apri 2. scegli 3. conferma.", "È «molto utile» per chi studia.", "Va bene, lo faccio subito",
        ).forEach { assertFalse(Flag.INCOMPLETE in eval("q", it).flags, it) }
    }

    // ---- degenerate / empty -----------------------------------------------------------------------------
    @Test fun `empty and degenerate`() {
        assertTrue(Flag.EMPTY in eval("q", "   ").flags)
        assertTrue(Flag.DEGENERATE in eval("q", "212121").flags)
    }

    // ---- echo of an earlier answer ----------------------------------------------------------------------
    private val langAnswer = "La lingua ufficiale dell'Italia è l'italiano, parlato da quasi tutta la popolazione insieme a vari dialetti."

    @Test fun `a near copy of the answer to a different question is flagged`() {
        val a = eval("Quanto è grande l'Asia?", langAnswer, listOf(langAnswer), listOf("Che lingua si parla in Italia?"))
        assertTrue(Flag.ECHO_OF_EARLIER_ANSWER in a.flags)
        assertTrue(a.retryable)
    }

    @Test fun `repeating an answer to the SAME question is legitimate`() {
        val a = eval("Che lingua si parla in Italia?", langAnswer, listOf(langAnswer), listOf("Che lingua si parla in Italia?"))
        assertFalse(Flag.ECHO_OF_EARLIER_ANSWER in a.flags)
    }

    @Test fun `different answers on a related topic are not echoes`() {
        val a = eval(
            "E in Spagna?", "In Spagna la lingua ufficiale è lo spagnolo, ma in alcune regioni si parlano anche catalano, basco e galiziano.",
            listOf(langAnswer), listOf("Che lingua si parla in Italia?"),
        )
        assertFalse(Flag.ECHO_OF_EARLIER_ANSWER in a.flags)
    }

    // ---- numbers & provenance ---------------------------------------------------------------------------
    @Test fun `numeric claims need three digits a decimal or a unit`() {
        assertEquals(emptyList(), AnswerQuality.numericClaims("Ci sono 7 giorni e 3 opzioni."))
        assertTrue(AnswerQuality.numericClaims("Ha circa 44 milioni di km²").isNotEmpty())
        assertTrue(AnswerQuality.numericClaims("Costruito nel 1861").isNotEmpty())
        assertTrue(AnswerQuality.numericClaims("Pesa 3,5 grammi").isNotEmpty())
        assertTrue(AnswerQuality.numericClaims("Cresce del 12%").isNotEmpty())
    }

    @Test fun `numbers the user typed are not claims`() {
        assertEquals(emptyList(), eval("Quanto fa 1024 più 1?", "Hai scritto 1024, quindi parti da 1024.").numericClaims)
    }

    @Test fun `unverified general numbers get an honest note and other answers do not`() {
        val withNum = eval("Quanto è alta la torre?", "La torre è alta circa 330 metri.")
        assertTrue(AnswerQuality.present("La torre è alta circa 330 metri.", Provenance.MODEL_GENERAL, withNum).endsWith(AnswerQuality.NOTE_UNVERIFIED))
        val noNum = eval("Che cos'è un SSD?", "Un SSD è una memoria a stato solido.")
        assertEquals("Un SSD è una memoria a stato solido.", AnswerQuality.present("Un SSD è una memoria a stato solido.", Provenance.MODEL_GENERAL, noNum))
        // Tool results are authoritative: never annotated.
        val tool = eval("Quanto fa 12 per 12?", "Fa 144.")
        assertEquals("Fa 144.", AnswerQuality.present("Fa 144.", Provenance.TOOL, tool))
    }

    @Test fun `source grounded answers flag only numbers absent from the sources`() {
        val evidence = "- [manuale p.2] La garanzia dura 24 mesi dalla data di acquisto. Il costo del ricambio è 150 euro."
        val ok = eval("Quanto dura la garanzia?", "Secondo il manuale la garanzia dura 24 mesi e il ricambio costa 150 euro.", ev = evidence)
        assertFalse(Flag.UNSUPPORTED_NUMBERS in ok.flags)
        val bad = eval("Quanto dura la garanzia?", "La garanzia dura 36 mesi e il ricambio costa 150 euro.", ev = evidence)
        assertTrue(Flag.UNSUPPORTED_NUMBERS in bad.flags)
        assertTrue(AnswerQuality.present("La garanzia dura 36 mesi", Provenance.SOURCE, bad).endsWith(AnswerQuality.NOTE_NOT_IN_SOURCES))
        assertFalse(bad.retryable, "an unsupported number is annotated, not regenerated")
    }

    @Test fun `presentation notes are never replayed to the model`() {
        val shown = "La torre è alta circa 330 metri.\n" + AnswerQuality.NOTE_UNVERIFIED
        assertEquals("La torre è alta circa 330 metri.", AnswerQuality.stripPresentationNotes(shown))
    }
}
