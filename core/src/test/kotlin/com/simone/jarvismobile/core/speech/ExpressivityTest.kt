package com.simone.jarvismobile.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpressivityTest {

    @Test
    fun `hidden metadata tag is read and stripped from the text`() {
        val plan = ExpressivePlanner.plan(
            "Tranquillo, ci penso io. ⟦tts: emotion=reassuring; intensity=0.4⟧",
        )
        assertEquals(Emotion.GENTLE, plan.emotion)
        assertTrue("⟦" !in plan.cleanedText && "tts" !in plan.cleanedText, plan.cleanedText)
        assertEquals("Tranquillo, ci penso io.", plan.cleanedText)
    }

    @Test
    fun `bracket form of the tag is also parsed`() {
        val plan = ExpressivePlanner.plan("Ecco fatto! [[tts emotion=happy]]")
        assertEquals(Emotion.HAPPY, plan.emotion)
        assertEquals("Ecco fatto!", plan.cleanedText)
    }

    @Test
    fun `heuristic picks a greeting`() {
        assertEquals(Emotion.GREETING, ExpressivePlanner.plan("Ciao Simone, come stai?").emotion)
    }

    @Test
    fun `heuristic picks urgency and seriousness`() {
        assertEquals(Emotion.URGENT, ExpressivePlanner.plan("Esci subito, in fretta!").emotion)
        assertEquals(Emotion.SERIOUS, ExpressivePlanner.plan("Attenzione: ricordati di chiudere il gas.").emotion)
    }

    @Test
    fun `a bad result stays composed and gentle`() {
        assertEquals(
            Emotion.GENTLE,
            ExpressivePlanner.plan("Purtroppo non sono riuscito a completare l'operazione.").emotion,
        )
    }

    @Test
    fun `a plain short answer stays warm and near-neutral`() {
        val plan = ExpressivePlanner.plan("Sono le nove e mezza.")
        assertEquals(Emotion.WARM, plan.emotion)
        // Near the base: the blend is deliberately gentle so normal talk isn't theatrical.
        assertTrue(kotlin.math.abs(plan.style.rate - SpeechStyle.NATURALE.rate) < 0.06f, plan.style.toString())
    }

    @Test
    fun `intensity scales how far the style moves`() {
        val text = "Esci subito, in fretta!"
        val low = ExpressivePlanner.plan(text, intensityScale = ExpressivePlanner.LOW).style.rate
        val high = ExpressivePlanner.plan(text, intensityScale = ExpressivePlanner.HIGH).style.rate
        // Urgent pushes rate up; higher intensity pushes it further.
        assertTrue(high > low, "low=$low high=$high")
    }

    @Test
    fun `the style stays within safe bounds`() {
        val s = ExpressivePlanner.plan("Esci subito!", intensityScale = 5f).style
        assertTrue(s.rate in SpeechStyle.MIN_RATE..SpeechStyle.MAX_RATE)
        assertTrue(s.pitch in SpeechStyle.MIN_PITCH..SpeechStyle.MAX_PITCH)
    }
}
