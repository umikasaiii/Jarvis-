package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalTime

/** Rough part of a day, used to filter "cosa devo fare oggi pomeriggio". */
enum class DayPeriod(val from: LocalTime, val to: LocalTime) {
    MATTINA(LocalTime.of(5, 0), LocalTime.of(12, 0)),
    POMERIGGIO(LocalTime.of(12, 0), LocalTime.of(18, 0)),
    SERA(LocalTime.of(18, 0), LocalTime.of(23, 59)),
    ;

    fun contains(t: LocalTime): Boolean = !t.isBefore(from) && t.isBefore(to)
}

/**
 * One dated item in JARVIS's agenda.
 *
 * Reminders are stored as structured entries — a real date and an optional time —
 * rather than free text with "domani" buried in the description. That is what
 * lets JARVIS answer "cosa devo fare oggi pomeriggio", sort by urgency, and
 * later fire a notification at the right moment.
 */
data class AgendaEntry(
    val date: LocalDate,
    val time: LocalTime? = null,
    val text: String,
    val done: Boolean = false,
) {
    /**
     * Obsidian-friendly Markdown line that is also machine-readable:
     * `- [ ] 2026-08-07 15:00 — tagliare i capelli`
     */
    fun toMarkdown(): String {
        val box = if (done) "- [x]" else "- [ ]"
        val stamp = if (time != null) "$date ${"%02d:%02d".format(time.hour, time.minute)}" else "$date"
        return "$box $stamp — $text"
    }

    companion object {
        private val LINE = Regex("""^-\s*\[( |x|X)]\s*(\d{4}-\d{2}-\d{2})(?:\s+(\d{1,2}):(\d{2}))?\s*[—\-–]\s*(.+)$""")

        /** Parses a line produced by [toMarkdown]; null when it isn't one. */
        fun parse(line: String): AgendaEntry? {
            val m = LINE.find(line.trim()) ?: return null
            val date = runCatching { LocalDate.parse(m.groupValues[2]) }.getOrNull() ?: return null
            val h = m.groupValues[3].toIntOrNull()
            val min = m.groupValues[4].toIntOrNull()
            val time = if (h != null && min != null && h in 0..23 && min in 0..59) {
                LocalTime.of(h, min)
            } else {
                null
            }
            val text = m.groupValues[5].trim()
            if (text.isEmpty()) return null
            return AgendaEntry(date, time, text, done = m.groupValues[1].lowercase() == "x")
        }
    }
}
