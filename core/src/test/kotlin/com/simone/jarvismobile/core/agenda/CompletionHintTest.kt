package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompletionHintTest {

    private val today = LocalDate.of(2026, 8, 7)

    private fun entry(text: String, done: Boolean = false) =
        AgendaEntry(today, LocalTime.of(10, 0), text, done)

    private val agenda = listOf(
        entry("dentista"),
        entry("revisione auto"),
        entry("comprare il latte"),
    )

    @Test
    fun `a past statement proposes the matching entry`() {
        assertEquals("dentista", CompletionHint.detect("sono andato dal dentista", agenda)?.text)
        assertEquals("revisione auto", CompletionHint.detect("ho fatto la revisione auto", agenda)?.text)
    }

    @Test
    fun `a question is not a report`() {
        assertNull(CompletionHint.detect("sono andato dal dentista?", agenda))
    }

    @Test
    fun `a future or neutral sentence proposes nothing`() {
        assertNull(CompletionHint.detect("domani vado dal dentista", agenda))
        assertNull(CompletionHint.detect("il dentista", agenda))
    }

    @Test
    fun `an unrelated past statement proposes nothing`() {
        assertNull(CompletionHint.detect("ho fatto colazione", agenda))
    }

    @Test
    fun `entries already done are ignored`() {
        val closed = listOf(entry("dentista", done = true))
        assertNull(CompletionHint.detect("sono andato dal dentista", closed))
    }

    @Test
    fun `ambiguity resolves to nothing rather than to a guess`() {
        val twins = listOf(entry("chiamare Marco"), entry("chiamare Marco Rossi"))
        assertNull(CompletionHint.detect("ho chiamato Marco", twins))
    }

    @Test
    fun `an empty agenda is safe`() {
        assertNull(CompletionHint.detect("sono andato dal dentista", emptyList()))
    }
}
