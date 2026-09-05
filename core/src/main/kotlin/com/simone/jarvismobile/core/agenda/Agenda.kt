package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure logic over a list of [AgendaEntry]: how the agenda file is written and
 * read back, how it is filtered for "cosa devo fare oggi pomeriggio", and how a
 * date or a duration is said out loud in Italian.
 *
 * Kept in `:core` (no Android) so all of it is unit-tested on the JVM. The
 * Android side only does file I/O through the Storage Access Framework.
 */
object Agenda {

    const val FILE_HEADER = "# Agenda di JARVIS"

    private val MONTHS = arrayOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
    )

    private val WEEKDAYS = arrayOf(
        "lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica",
    )

    /** Reads every agenda line out of a Markdown file, ignoring anything else. */
    fun parseFile(text: String): List<AgendaEntry> =
        text.lineSequence().mapNotNull { AgendaEntry.parse(it) }.toList()

    /** Renders the whole agenda back to Markdown, sorted, ready to be written. */
    fun renderFile(entries: List<AgendaEntry>): String = buildString {
        append(FILE_HEADER).append("\n\n")
        sorted(entries).forEach { append(it.toMarkdown()).append('\n') }
    }

    /**
     * Chronological order; entries without a time come first within their day,
     * and undated tasks sort last (they have no place on the timeline). Within
     * the same slot the manual [AgendaEntry.order] wins, then the text.
     */
    fun sorted(entries: List<AgendaEntry>): List<AgendaEntry> =
        entries.sortedWith(
            compareBy(
                { it.date ?: LocalDate.MAX },
                { it.time ?: LocalTime.MIN },
                { it.order },
                { it.text },
            ),
        )

    /**
     * The entries to read out: optionally restricted to one [day] (or, when
     * [toDay] is also given, an inclusive date RANGE [day, toDay] — § FASE
     * 2A.9, added for "E durante tutta la settimana prossima?"-style
     * requests that `DayPeriod`, a time-of-day-only concept, cannot express)
     * and one part of the day ([period]). With no [day], everything from
     * [today] onwards, so past items stop being announced. [toDay] is
     * ignored unless [day] is also set — a range needs both ends.
     */
    fun filter(
        entries: List<AgendaEntry>,
        today: LocalDate,
        day: LocalDate? = null,
        period: DayPeriod? = null,
        includeDone: Boolean = false,
        toDay: LocalDate? = null,
    ): List<AgendaEntry> = sorted(
        entries.filter { e ->
            if (!includeDone && e.done) return@filter false
            // An undated task belongs to no specific day and isn't "past".
            if (day != null && toDay != null) {
                val d = e.date ?: return@filter false
                if (d.isBefore(day) || d.isAfter(toDay)) return@filter false
            } else {
                if (day != null && e.date != day) return@filter false
                if (day == null && e.date != null && e.date.isBefore(today)) return@filter false
            }
            if (period != null) {
                val t = e.time ?: return@filter false
                if (!period.contains(t)) return@filter false
            }
            true
        },
    )

    /** "oggi", "domani", "venerdì", "12 agosto" — how a human would say the date. */
    fun humanDate(date: LocalDate?, today: LocalDate): String = when {
        date == null -> "senza data"
        date == today -> "oggi"
        date == today.plusDays(1) -> "domani"
        date == today.plusDays(2) -> "dopodomani"
        date.isAfter(today) && date.isBefore(today.plusDays(7)) ->
            WEEKDAYS[date.dayOfWeek.value - 1]
        else -> "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"
    }

    /**
     * A fully specified date for lists — "oggi", "domani", or otherwise the
     * unambiguous "martedì 11 agosto" (weekday + day + month). Used by the
     * Attività screen, where a vague "dopodomani" or a bare "venerdì" doesn't
     * tell you which day it actually is.
     */
    fun fullDate(date: LocalDate?, today: LocalDate): String = when {
        date == null -> "senza data"
        date == today -> "oggi"
        date == today.plusDays(1) -> "domani"
        else -> "${WEEKDAYS[date.dayOfWeek.value - 1]} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"
    }

    /** "15:00" or empty when the entry has no time. */
    fun humanTime(time: LocalTime?): String =
        if (time == null) "" else "%02d:%02d".format(time.hour, time.minute)

    /** One spoken line: "domani alle 15:00, tagliare i capelli". */
    fun speak(entry: AgendaEntry, today: LocalDate): String {
        val whenPart = humanDate(entry.date, today)
        val timePart = entry.time?.let { " alle ${humanTime(it)}" }.orEmpty()
        return "$whenPart$timePart, ${entry.text}"
    }

    /** "477" → "7 ore e 57 minuti". Used to answer "quanto manca alle 16?". */
    fun humanMinutes(minutes: Long): String {
        if (minutes <= 0) return "meno di un minuto"
        val h = minutes / 60
        val m = minutes % 60
        val parts = buildList {
            if (h > 0) add(if (h == 1L) "1 ora" else "$h ore")
            if (m > 0) add(if (m == 1L) "1 minuto" else "$m minuti")
        }
        return parts.joinToString(" e ")
    }
}
