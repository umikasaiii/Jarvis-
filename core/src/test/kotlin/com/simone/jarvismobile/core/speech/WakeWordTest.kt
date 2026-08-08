package com.simone.jarvismobile.core.speech

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeWordTest {

    @Test
    fun `hears the word inside a phrase`() {
        assertTrue(WakeWord.matches("Ehi Jarvis", "Jarvis"))
        assertTrue(WakeWord.matches("ok jarvis, accendi la torcia", "jarvis"))
        assertTrue(WakeWord.matches("Jarvis?", "Jarvis"))
    }

    @Test
    fun `is accent and case insensitive`() {
        assertTrue(WakeWord.matches("EHI JÀRVIS", "jarvis"))
    }

    @Test
    fun `does not fire on a word that merely contains it`() {
        assertFalse(WakeWord.matches("giravolta", "jarvis"))
        assertFalse(WakeWord.matches("jarvista", "jarvis"))
    }

    @Test
    fun `empty inputs never match`() {
        assertFalse(WakeWord.matches("", "jarvis"))
        assertFalse(WakeWord.matches("jarvis", ""))
        assertFalse(WakeWord.matches("   ", "  "))
    }
}
