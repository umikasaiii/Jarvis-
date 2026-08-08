package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgendaEntryTest {

    @Test
    fun `round trip with time`() {
        val e = AgendaEntry(LocalDate.of(2026, 8, 7), LocalTime.of(15, 0), "tagliare i capelli")
        assertEquals(
            "- [ ] 2026-08-07 15:00 — tagliare i capelli <!-- jarvis:id=${e.id};alerts= -->",
            e.toMarkdown(),
        )
        assertEquals(e, AgendaEntry.parse(e.toMarkdown()))
    }

    @Test
    fun `round trip without time`() {
        val e = AgendaEntry(LocalDate.of(2026, 8, 7), null, "cambiare la lampadina")
        assertEquals(
            "- [ ] 2026-08-07 — cambiare la lampadina <!-- jarvis:id=${e.id};alerts= -->",
            e.toMarkdown(),
        )
        assertEquals(e, AgendaEntry.parse(e.toMarkdown()))
    }

    @Test
    fun `done entries are recognised`() {
        val parsed = AgendaEntry.parse("- [x] 2026-08-07 09:30 — revisione")
        assertEquals(true, parsed?.done)
        assertEquals(LocalTime.of(9, 30), parsed?.time)
    }

    @Test
    fun `a plain note is not an agenda line`() {
        assertNull(AgendaEntry.parse("- [2026-08-05 21:55] cambiare la ruota"))
        assertNull(AgendaEntry.parse("qualcosa di casuale"))
    }

    @Test
    fun `hyphen separator also parses`() {
        val parsed = AgendaEntry.parse("- [ ] 2026-08-09 - palestra")
        assertEquals(LocalDate.of(2026, 8, 9), parsed?.date)
        assertEquals("palestra", parsed?.text)
    }

    @Test
    fun `an undated task round-trips with no date stamp`() {
        val e = AgendaEntry(date = null, text = "comprare il pane", list = "Spesa")
        assertEquals(
            "- [ ] — comprare il pane <!-- jarvis:id=${e.id};alerts=;list=Spesa -->",
            e.toMarkdown(),
        )
        val back = AgendaEntry.parse(e.toMarkdown())
        assertEquals(e, back)
        assertEquals(true, back?.undated)
    }

    @Test
    fun `the full task shape survives the round trip`() {
        val e = AgendaEntry(
            date = LocalDate.of(2026, 8, 8),
            time = LocalTime.of(12, 30),
            text = "Jarvis",
            done = true,
            list = "Work",
            starred = true,
            notes = "controllare la dashboard",
            parentId = "parent-1",
            order = 3,
            completedAt = LocalDate.of(2026, 8, 8),
        )
        assertEquals(e, AgendaEntry.parse(e.toMarkdown()))
    }

    @Test
    fun `default list and flags are omitted from the line`() {
        val e = AgendaEntry(LocalDate.of(2026, 8, 7), null, "x")
        // No list=, star=, notes=, parent=, order=, done= for a plain entry.
        assertEquals("- [ ] 2026-08-07 — x <!-- jarvis:id=${e.id};alerts= -->", e.toMarkdown())
    }

    @Test
    fun `notes with delimiters are sanitised, not corrupt the line`() {
        val e = AgendaEntry(LocalDate.of(2026, 8, 7), null, "x", notes = "a; b --> c")
        val back = AgendaEntry.parse(e.toMarkdown())!!
        // The parse still succeeds and the id/text are intact.
        assertEquals(e.id, back.id)
        assertEquals("x", back.text)
        assertEquals("a, b → c", back.notes)
    }

    @Test
    fun `multiple reminder rules survive the markdown round trip`() {
        val entry = AgendaEntry(
            date = LocalDate.of(2026, 8, 10),
            time = LocalTime.of(15, 30),
            text = "dentista",
            alerts = listOf(
                ReminderAlert(ReminderAlertType.MORNING_OF),
                ReminderAlert(ReminderAlertType.ONE_DAY_BEFORE),
                ReminderAlert(ReminderAlertType.CUSTOM, LocalDateTime.of(2026, 8, 8, 20, 0)),
            ),
        )
        assertEquals(entry, AgendaEntry.parse(entry.toMarkdown()))
    }
}
