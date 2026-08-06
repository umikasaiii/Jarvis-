package com.simone.jarvismobile.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechShaperTest {

    @Test
    fun `markdown never reaches the synthesiser`() {
        val out = SpeechShaper.normalize("**Attenzione**: leggi il *manuale* e vedi [qui](http://x.y)")
        assertEquals("Attenzione: leggi il manuale e vedi qui", out)
    }

    @Test
    fun `bullets and headings are flattened`() {
        val out = SpeechShaper.normalize("# Titolo\n- primo\n- secondo")
        assertFalse(out.contains("#"))
        assertFalse(out.contains("-"))
        assertTrue(out.contains("primo"))
    }

    @Test
    fun `times are spoken the Italian way`() {
        assertEquals("alle 15 e 30", SpeechShaper.normalize("alle 15:30"))
        assertEquals("alle 16", SpeechShaper.normalize("alle 16:00"))
    }

    @Test
    fun `units and decimals are read as words`() {
        assertEquals("batteria al 68 per cento", SpeechShaper.normalize("batteria al 68%"))
        assertEquals("costa 12,5 euro", SpeechShaper.normalize("costa 12.5€"))
        assertEquals("fanno 20 gradi", SpeechShaper.normalize("fanno 20°C"))
    }

    @Test
    fun `abbreviations are expanded`() {
        assertEquals("per esempio la moto, eccetera", SpeechShaper.normalize("es. la moto, ecc."))
        assertEquals("50 chilometri in 30 minuti", SpeechShaper.normalize("50 km in 30 min"))
    }

    @Test
    fun `a sentence becomes segments with pauses between them`() {
        val segs = SpeechShaper.shape("Ciao Simone. Come stai?", SpeechStyle.NATURALE)
        assertEquals(listOf("Ciao Simone.", "Come stai?"), segs.map { it.text })
        assertTrue(segs.first().pauseMs > 0, "serve una pausa dopo la prima frase")
        assertEquals(0L, segs.last().pauseMs, "nessuna attesa dopo l'ultima parola")
    }

    @Test
    fun `expressiveness controls how long the pauses are`() {
        val text = "Prima frase. Seconda frase."
        val calmo = SpeechShaper.shape(text, SpeechStyle.CALMO).first().pauseMs
        val naturale = SpeechShaper.shape(text, SpeechStyle.NATURALE).first().pauseMs
        val rapido = SpeechShaper.shape(text, SpeechStyle.RAPIDO).first().pauseMs
        assertTrue(calmo > naturale, "calmo deve respirare piu di naturale")
        assertTrue(naturale > rapido, "rapido deve respirare meno di naturale")
    }

    @Test
    fun `a flat style still speaks, just without added silence`() {
        val flat = SpeechStyle(expressiveness = 0f)
        val segs = SpeechShaper.shape("Prima. Seconda.", flat)
        assertEquals(2, segs.size)
        assertTrue(segs.all { it.pauseMs == 0L })
    }

    @Test
    fun `list items are separated`() {
        val segs = SpeechShaper.shape("- pane\n- latte\n- caffe", SpeechStyle.NATURALE)
        assertEquals(listOf("pane", "latte", "caffe"), segs.map { it.text })
        assertTrue(segs.first().pauseMs > 0)
    }

    @Test
    fun `blank input produces nothing to speak`() {
        assertTrue(SpeechShaper.shape("   \n  ").isEmpty())
        assertTrue(SpeechShaper.shape("```\ncodice\n```").isEmpty())
    }

    @Test
    fun `style bounds keep the voice usable`() {
        val wild = SpeechStyle(rate = 9f, pitch = 0.01f, pauseScale = 99f, expressiveness = 5f).coerced()
        assertEquals(SpeechStyle.MAX_RATE, wild.rate)
        assertEquals(SpeechStyle.MIN_PITCH, wild.pitch)
        assertEquals(3f, wild.pauseScale)
        assertEquals(1f, wild.expressiveness)
    }

    @Test
    fun `presets are ordered and complete`() {
        assertEquals(listOf("Calmo", "Naturale", "Espressivo", "Rapido"), SpeechStyle.PRESETS.keys.toList())
    }
}
