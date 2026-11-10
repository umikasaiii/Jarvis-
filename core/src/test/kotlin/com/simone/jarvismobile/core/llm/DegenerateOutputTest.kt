package com.simone.jarvismobile.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DegenerateOutputTest {
    @Test fun `the reply observed on the Honor 200 is recognised`() {
        val v = DegenerateOutput.analyze("212121")
        assertTrue(v.degenerate)
        assertEquals(DegenerateOutput.Reason.PERIODIC_SHORT, v.reason)
        assertEquals(2, v.unitLength)
    }

    @Test fun `longer digit loops and whitespace separated loops are recognised`() {
        assertTrue(DegenerateOutput.analyze("2121212121212121").degenerate)
        assertTrue(DegenerateOutput.analyze("21 21 21 21").degenerate)
        assertTrue(DegenerateOutput.analyze("000000").degenerate)
        assertTrue(DegenerateOutput.analyze("....... ").degenerate)
    }

    @Test fun `a looping token sequence is recognised`() {
        assertTrue(DegenerateOutput.analyze("ciao ciao ciao ciao ciao ciao ciao").degenerate)
        assertEquals(DegenerateOutput.Reason.TOKEN_LOOP, DegenerateOutput.analyze("il gatto dorme il gatto dorme il gatto dorme il gatto dorme").reason)
    }

    @Test fun `ordinary answers are never flagged`() {
        listOf(
            "Roma.", "Parigi è la capitale della Francia.", "Sono le 10:30.", "42", "1000000", "3,14159",
            "Ha ha ha", "no no no", "Il risultato è 212121 euro, ma il totale cambia.",
            "L'SSD conserva i dati anche quando il telefono è spento, la RAM no.",
            "", "ok",
        ).forEach { assertFalse(DegenerateOutput.analyze(it).degenerate, "false positive: '$it'") }
    }

    @Test fun `short letter patterns need more evidence than digit patterns`() {
        assertFalse(DegenerateOutput.analyze("abab").degenerate)
        assertFalse(DegenerateOutput.analyze("ababab").degenerate)
        assertTrue(DegenerateOutput.analyze("abababababab").degenerate)
    }
}
