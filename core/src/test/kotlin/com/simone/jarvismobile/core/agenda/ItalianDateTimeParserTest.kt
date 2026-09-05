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
    fun `a leading connective left over from a mid-sentence date is stripped`() {
        // Real device bug: "domani alle 21" sits in the MIDDLE of the sentence,
        // so once it's removed the dangling "di" that connected it to the verb
        // must not survive into the title ("di sistemare…" -> "sistemare…").
        val r = ItalianDateTimeParser.parse(
            "domani alle 21 di sistemare tubo cameretta e lasciare chiavi della cantina",
            now,
        )
        assertEquals("sistemare tubo cameretta e lasciare chiavi della cantina", r.remainder)
    }

    @Test
    fun `a leading che connective is stripped the same way`() {
        // The parser lowercases its remainder unconditionally (see `lower` above),
        // so the expectation is lowercase regardless of the input's casing.
        val r = ItalianDateTimeParser.parse("domani alle 9 che devo chiamare Luca", now)
        assertEquals("devo chiamare luca", r.remainder)
    }

    @Test
    fun `a non-leading della is never touched`() {
        // "della cantina" mid-sentence must survive — only a LEADING connective
        // is grammatical scaffolding; the same word later is real content.
        val r = ItalianDateTimeParser.parse("lasciare le chiavi della cantina domani", now)
        assertEquals("lasciare le chiavi della cantina", r.remainder)
    }

    @Test
    fun `a leading ho plus article is stripped, real device bug`() {
        // "sabato ho il dentista" saved a reminder literally titled "ho il
        // dentista": stating that you HAVE something scheduled is the same
        // kind of leftover auxiliary as "il fatto che", just with a verb.
        val r = ItalianDateTimeParser.parse("sabato ho il dentista", now)
        assertEquals("dentista", r.remainder)
    }

    @Test
    fun `ho without a following article is real content, not stripped`() {
        // "ho fatto la spesa" is a genuine statement — only "ho" + a bare
        // article is the narrow leftover-auxiliary pattern being stripped.
        val r = ItalianDateTimeParser.parse("domani ho fatto la spesa", now)
        assertEquals("ho fatto la spesa", r.remainder)
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

    // --- § FASE 2A.7 RELEASE GATE 2 — date boundaries, clock injected via `now` ----------

    @Test
    fun `domani crosses a month boundary correctly`() {
        val endOfMonth = LocalDateTime.of(2026, 8, 31, 10, 0)
        val r = ItalianDateTimeParser.parse("domani revisione", endOfMonth)
        assertEquals(LocalDate.of(2026, 9, 1), r.date)
    }

    @Test
    fun `domani crosses a year boundary correctly`() {
        val newYearsEve = LocalDateTime.of(2026, 12, 31, 23, 0)
        val r = ItalianDateTimeParser.parse("domani revisione", newYearsEve)
        assertEquals(LocalDate.of(2027, 1, 1), r.date)
    }

    @Test
    fun `dopodomani crosses a year boundary correctly`() {
        val newYearsEve = LocalDateTime.of(2026, 12, 31, 23, 0)
        val r = ItalianDateTimeParser.parse("dopodomani revisione", newYearsEve)
        assertEquals(LocalDate.of(2027, 1, 2), r.date)
    }

    @Test
    fun `a request one minute before midnight still resolves domani to the correct calendar day`() {
        val almostMidnight = LocalDateTime.of(2026, 8, 6, 23, 59)
        val r = ItalianDateTimeParser.parse("domani revisione", almostMidnight)
        assertEquals(LocalDate.of(2026, 8, 7), r.date)
    }

    @Test
    fun `a request one minute after midnight resolves domani from the new calendar day, not the previous one`() {
        val justAfterMidnight = LocalDateTime.of(2026, 8, 7, 0, 1)
        val r = ItalianDateTimeParser.parse("domani revisione", justAfterMidnight)
        assertEquals(LocalDate.of(2026, 8, 8), r.date)
    }

    @Test
    fun `weekday name from a Sunday rolls over into the next Monday, not staying inside the same week`() {
        // 9 Aug 2026 is a Sunday.
        val sunday = LocalDateTime.of(2026, 8, 9, 10, 0)
        val r = ItalianDateTimeParser.parse("lunedì revisione", sunday)
        assertEquals(LocalDate.of(2026, 8, 10), r.date)
    }

    @Test
    fun `naming the current weekday itself means next week, not today`() {
        // 6 Aug 2026 is a Thursday; asking for "giovedì" on a Thursday must
        // never silently resolve to today (an already-past instruction).
        val thursday = LocalDateTime.of(2026, 8, 6, 10, 0)
        val r = ItalianDateTimeParser.parse("giovedì revisione", thursday)
        assertEquals(LocalDate.of(2026, 8, 13), r.date)
    }

    @Test
    fun `an explicit day-month date spanning into next year is resolved correctly, not silently kept in the current year`() {
        // Asking for "1 gennaio" from December must mean next January, not a
        // date already in the past.
        val midDecember = LocalDateTime.of(2026, 12, 15, 10, 0)
        val r = ItalianDateTimeParser.parse("il 1 gennaio revisione", midDecember)
        assertEquals(LocalDate.of(2027, 1, 1), r.date)
    }
}
