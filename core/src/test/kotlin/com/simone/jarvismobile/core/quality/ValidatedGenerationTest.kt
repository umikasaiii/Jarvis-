package com.simone.jarvismobile.core.quality

import com.simone.jarvismobile.core.quality.AnswerQuality.Flag
import com.simone.jarvismobile.core.quality.AnswerQuality.Provenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TEST KIND: JVM, scripted FAKE model outputs. Proves the orchestration policy, not the real model. */
class ValidatedGenerationTest {
    private fun run(
        outputs: List<String?>,
        question: String = "Qual è la capitale della Francia?",
        earlier: List<String> = emptyList(), earlierQ: List<String> = emptyList(),
        evidence: String? = null, provenance: Provenance = Provenance.MODEL_GENERAL,
    ): Pair<ValidatedGeneration.Result, Int> {
        var calls = 0
        val r = runBlocking {
            ValidatedGeneration.run(question, earlier, earlierQ, evidence, provenance, { it }) { retry, _ ->
                val out = outputs.getOrNull(calls); calls++
                assertEquals(calls > 1, retry, "the retry flag must be set exactly on the second call")
                out
            }
        }
        return r to calls
    }

    @Test fun `a good first answer is used with no retry`() {
        val (r, calls) = run(listOf("La capitale della Francia è Parigi."))
        assertEquals("La capitale della Francia è Parigi.", r.text)
        assertEquals(1, calls); assertFalse(r.retried)
    }

    @Test fun `an incomplete first answer triggers exactly one retry and the complete one wins`() {
        val (r, calls) = run(listOf("La capitale della Francia è una città che si trova sulla", "La capitale della Francia è Parigi."))
        assertEquals("La capitale della Francia è Parigi.", r.text)
        assertEquals(2, calls); assertTrue(r.retried)
        assertTrue(Flag.INCOMPLETE in r.firstFlags)
    }

    @Test fun `never more than one retry`() {
        val (r, calls) = run(listOf("Parigi è la città di cui", "Parigi si trova sulla", "mai usato"))
        assertEquals(2, calls)
        assertEquals("Parigi si trova sulla".length >= "Parigi è la città di cui".length, r.text == "Parigi si trova sulla" )
    }

    @Test fun `an echo of an earlier answer is retried and an echo twice is never shown`() {
        val prev = "La lingua ufficiale dell'Italia è l'italiano, parlato da quasi tutta la popolazione insieme a vari dialetti."
        val (r, calls) = run(
            listOf(prev, prev), question = "Quanto è grande l'Asia?", earlier = listOf(prev), earlierQ = listOf("Che lingua si parla in Italia?"),
        )
        assertEquals(2, calls)
        assertNull(r.text, "a reply that belongs to an earlier turn must not be shown; the caller states the limit honestly")
    }

    @Test fun `an echo is replaced by a correct retry`() {
        val prev = "La lingua ufficiale dell'Italia è l'italiano, parlato da quasi tutta la popolazione insieme a vari dialetti."
        val (r, _) = run(
            listOf(prev, "L'Asia è il continente più esteso della Terra."), question = "Quanto è grande l'Asia?",
            earlier = listOf(prev), earlierQ = listOf("Che lingua si parla in Italia?"),
        )
        assertEquals("L'Asia è il continente più esteso della Terra.", r.text)
    }

    @Test fun `null output then a valid retry`() {
        val (r, calls) = run(listOf(null, "Parigi."))
        assertEquals("Parigi.", r.text); assertEquals(2, calls)
    }

    @Test fun `nothing usable yields null so the caller can be honest`() {
        val (r, _) = run(listOf(null, ""))
        assertNull(r.text)
    }

    @Test fun `numeric claims from memory get the note and tool answers do not`() {
        val (g, _) = run(listOf("L'Asia misura circa 44 milioni di km²."), question = "Quanto è grande l'Asia?")
        assertTrue(g.text!!.endsWith(AnswerQuality.NOTE_UNVERIFIED))
        val (t, _) = run(listOf("Fa 144."), question = "Quanto fa 12 per 12?", provenance = Provenance.TOOL)
        assertEquals("Fa 144.", t.text)
    }

    @Test fun `cancellation propagates untouched`() {
        assertFailsWith<CancellationException> {
            runBlocking {
                ValidatedGeneration.run("q", emptyList(), emptyList(), null, Provenance.MODEL_GENERAL, { it }) { _, _ -> throw CancellationException("stop") }
            }
        }
    }
}
