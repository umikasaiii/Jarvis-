package com.simone.jarvismobile.core.agenda

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * § FASE 2A.9 — a genuinely new, minimal capability: neither
 * [ItalianDateTimeParser] (single day/period only) nor `DayPeriod`
 * (time-of-day only) had any notion of a WEEK range before this phase, so
 * "E durante tutta la settimana prossima?" had no deterministic parser to
 * resolve against at all — this is not a duplicate of an existing parser,
 * it fills a real, previously-unfilled gap, exactly like
 * [ItalianDateTimeParser] itself fills the single-day gap. Pure, no I/O.
 */
object AgendaWeekRange {

    private val NEXT_WEEK = Regex("""(?i)\b(la\s+)?(prossima\s+settimana|settimana\s+prossima)\b""")
    private val LAST_WEEK = Regex("""(?i)\b(la\s+)?settimana\s+scorsa\b""")
    private val THIS_WEEK = Regex("""(?i)\b(questa\s+settimana|questa\s+intera\s+settimana)\b""")

    /**
     * The inclusive [LocalDate] range (Monday-Sunday) [text] refers to, or
     * `null` when it names no week at all — a caller falls back to its own
     * single-day parser in that case, never guesses a range.
     */
    fun resolve(text: String, now: LocalDateTime): ClosedRange<LocalDate>? {
        val today = now.toLocalDate()
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when {
            NEXT_WEEK.containsMatchIn(text) -> {
                val monday = thisMonday.plusWeeks(1)
                monday..monday.plusDays(6)
            }
            LAST_WEEK.containsMatchIn(text) -> {
                val monday = thisMonday.minusWeeks(1)
                monday..monday.plusDays(6)
            }
            THIS_WEEK.containsMatchIn(text) -> thisMonday..thisMonday.plusDays(6)
            else -> null
        }
    }
}
