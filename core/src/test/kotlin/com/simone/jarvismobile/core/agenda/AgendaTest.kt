package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgendaTest {

    private val today = LocalDate.of(2026, 8, 6) // Thursday

    private val entries = listOf(
        AgendaEntry(today, LocalTime.of(16, 0), "chiamare il gommista"),
        AgendaEntry(today, LocalTime.of(9, 0), "colazione con Luca"),
        AgendaEntry(today.plusDays(1), null, "revisione auto"),
        AgendaEntry(today.minusDays(2), null, "cosa vecchia"),
        AgendaEntry(today, LocalTime.of(20, 30), "cena", done = true),
    )

    @Test
    fun `file round trip keeps every entry`() {
        val text = Agenda.renderFile(entries)
        assertTrue(text.startsWith(Agenda.FILE_HEADER))
        assertEquals(entries.size, Agenda.parseFile(text).size)
        assertEquals(Agenda.sorted(entries), Agenda.parseFile(text))
    }

    @Test
    fun `toDay filters an inclusive date range instead of one exact day`() {
        val entries = listOf(
            AgendaEntry(today, null, "oggi"),
            AgendaEntry(today.plusDays(3), null, "tra 3 giorni"),
            AgendaEntry(today.plusDays(6), null, "tra 6 giorni"),
            AgendaEntry(today.plusDays(9), null, "tra 9 giorni, fuori range"),
            AgendaEntry(null, null, "senza data"),
        )
        val result = Agenda.filter(entries, today, day = today, toDay = today.plusDays(6))
        assertEquals(listOf("oggi", "tra 3 giorni", "tra 6 giorni"), result.map { it.text })
    }

    @Test
    fun `toDay is ignored without day, matching the previous single-day behavior`() {
        val entries = listOf(AgendaEntry(today, null, "oggi"))
        // day == null: toDay alone must never activate range mode.
        val result = Agenda.filter(entries, today, day = null, toDay = today.plusDays(6))
        assertEquals(1, result.size)
    }

    @Test
    fun `prose around the entries is ignored`() {
        val text = """
            # Agenda di JARVIS

            Qualche riga di appunti liberi.
            - [ ] 2026-08-07 09:30 — revisione
            - una nota normale, non un impegno
        """.trimIndent()
        val parsed = Agenda.parseFile(text)
        assertEquals(1, parsed.size)
        assertEquals("revisione", parsed.first().text)
    }

    @Test
    fun `sorted puts untimed entries first within the day`() {
        val day = today.plusDays(3)
        val sorted = Agenda.sorted(
            listOf(
                AgendaEntry(day, LocalTime.of(8, 0), "b"),
                AgendaEntry(day, null, "a"),
            ),
        )
        assertEquals(listOf("a", "b"), sorted.map { it.text })
    }

    @Test
    fun `filter drops past and done entries by default`() {
        val out = Agenda.filter(entries, today)
        assertEquals(listOf("colazione con Luca", "chiamare il gommista", "revisione auto"), out.map { it.text })
    }

    @Test
    fun `filter by day`() {
        val out = Agenda.filter(entries, today, day = today.plusDays(1))
        assertEquals(listOf("revisione auto"), out.map { it.text })
    }

    @Test
    fun `filter by afternoon only keeps timed entries in that window`() {
        val out = Agenda.filter(entries, today, day = today, period = DayPeriod.POMERIGGIO)
        assertEquals(listOf("chiamare il gommista"), out.map { it.text })
    }

    @Test
    fun `human date reads naturally`() {
        assertEquals("oggi", Agenda.humanDate(today, today))
        assertEquals("domani", Agenda.humanDate(today.plusDays(1), today))
        assertEquals("dopodomani", Agenda.humanDate(today.plusDays(2), today))
        assertEquals("domenica", Agenda.humanDate(today.plusDays(3), today))
        assertEquals("20 agosto", Agenda.humanDate(LocalDate.of(2026, 8, 20), today))
    }

    @Test
    fun `full date is specified for lists`() {
        assertEquals("oggi", Agenda.fullDate(today, today))
        assertEquals("domani", Agenda.fullDate(today.plusDays(1), today))
        // Where humanDate would say the vague "dopodomani" / "domenica", fullDate
        // spells out the weekday, day and month so a list is unambiguous.
        assertEquals("sabato 8 agosto", Agenda.fullDate(today.plusDays(2), today))
        assertEquals("giovedì 20 agosto", Agenda.fullDate(LocalDate.of(2026, 8, 20), today))
        assertEquals("senza data", Agenda.fullDate(null, today))
    }

    @Test
    fun `spoken line has day and time`() {
        val e = AgendaEntry(today.plusDays(1), LocalTime.of(15, 0), "tagliare i capelli")
        assertEquals("domani alle 15:00, tagliare i capelli", Agenda.speak(e, today))
    }

    @Test
    fun `human minutes`() {
        assertEquals("7 ore e 57 minuti", Agenda.humanMinutes(477))
        assertEquals("1 ora", Agenda.humanMinutes(60))
        assertEquals("1 minuto", Agenda.humanMinutes(1))
        assertEquals("45 minuti", Agenda.humanMinutes(45))
        assertEquals("meno di un minuto", Agenda.humanMinutes(0))
    }
}
