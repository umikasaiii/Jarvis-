package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItalianDateTimeParserTest {

    // Thursday 6 August 2026, 08:03 — the moment from the device screenshots.
    private val now = LocalDateTime.of(2026, 8, 6, 8, 3)

    @Test
    fun `domani is tomorrow`() {
        val r = ItalianDateTimeParser.parse("tagliarmi i capelli domani", now)
        assertEquals(LocalDate.of(2026, 8, 7), r.date)
        assertNull(r.time)
        assertEquals("tagliarmi i capelli", r.remainder)
    }

    @Test
    fun `dopodomani wins over domani`() {
        val r = ItalianDateTimeParser.parse("dopodomani", now)
        assertEquals(LocalDate.of(2026, 8, 8), r.date)
    }

    @Test
    fun `afternoon shifts a small hour`() {
        val r = ItalianDateTimeParser.parse("alle 4 di pomeriggio", now)
        assertEquals(LocalTime.of(16, 0), r.time)
        assertEquals(DayPeriod.POMERIGGIO, r.period)
    }

    @Test
    fun `explicit clock time is kept`() {
        val r = ItalianDateTimeParser.parse("appuntamento alle 15:30 domani", now)
        assertEquals(LocalTime.of(15, 30), r.time)
        assertEquals(LocalDate.of(2026, 8, 7), r.date)
        assertEquals("appuntamento", r.remainder)
    }

    @Test
    fun `half past is understood`() {
        val r = ItalianDateTimeParser.parse("alle sette e mezza", now)
        assertEquals(LocalTime.of(7, 30), r.time)
    }

    @Test
    fun `time already past today means tomorrow`() {
        val r = ItalianDateTimeParser.parse("alle 7:00", now)
        assertEquals(LocalDate.of(2026, 8, 7), r.date)
    }

    @Test
    fun `time later today stays today`() {
        val r = ItalianDateTimeParser.parse("alle 18:00", now)
        assertEquals(LocalDate.of(2026, 8, 6), r.date)
    }

    @Test
    fun `in three days`() {
        val r = ItalianDateTimeParser.parse("tra tre giorni", now)
        assertEquals(LocalDate.of(2026, 8, 9), r.date)
    }

    @Test
    fun `next weekday is in the future`() {
        // 6 Aug 2026 is a Thursday, so "venerdì" is the 7th.
        val r = ItalianDateTimeParser.parse("venerdì revisione", now)
        assertEquals(LocalDate.of(2026, 8, 7), r.date)
        assertTrue(r.remainder.contains("revisione"))
    }

    @Test
    fun `day and month`() {
        val r = ItalianDateTimeParser.parse("il 12 agosto", now)
        assertEquals(LocalDate.of(2026, 8, 12), r.date)
    }

    @Test
    fun `numeric date`() {
        val r = ItalianDateTimeParser.parse("il 20/09 controllo", now)
        assertEquals(LocalDate.of(2026, 9, 20), r.date)
    }

    @Test
    fun `no date at all`() {
        val r = ItalianDateTimeParser.parse("comprare il latte", now)
        assertNull(r.date)
        assertNull(r.time)
        assertEquals("comprare il latte", r.remainder)
    }

    // --- The bug from the screenshot ------------------------------------

    @Test
    fun `minutes until four in the afternoon`() {
        // 08:03 → 16:00 is 7h57m = 477 minutes (the model had said "2h45").
        assertEquals(477L, ItalianDateTimeParser.minutesUntil("alle 4:00 di pomeriggio", now))
    }

    @Test
    fun `minutes until a time that already passed rolls to tomorrow`() {
        val mins = ItalianDateTimeParser.minutesUntil("alle 7:00", now)!!
        // 08:03 today → 07:00 tomorrow is 22h57m.
        assertEquals(22L * 60 + 57, mins)
    }

    @Test
    fun `a day inferred from a bare time is not an explicit day`() {
        // "alle 18" means today, but the user did not choose a day — callers must
        // be able to tell the difference.
        assertEquals(false, ItalianDateTimeParser.parse("alle 18:00", now).dateExplicit)
        assertEquals(true, ItalianDateTimeParser.parse("domani alle 18:00", now).dateExplicit)
        assertEquals(true, ItalianDateTimeParser.parse("venerdì", now).dateExplicit)
    }

    @Test
    fun `minutes until is null without a time`() {
        assertNull(ItalianDateTimeParser.minutesUntil("domani", now))
    }
}
