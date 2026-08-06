package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class ReminderAlertType {
    AT_TIME,
    MORNING_OF,
    ONE_DAY_BEFORE,
    TWO_DAYS_BEFORE,
    THREE_DAYS_BEFORE,
    ONE_WEEK_BEFORE,
    CUSTOM,
}

/** One notification rule attached to an agenda entry. */
data class ReminderAlert(
    val type: ReminderAlertType,
    val customAt: LocalDateTime? = null,
) {
    init {
        require(type == ReminderAlertType.CUSTOM || customAt == null)
        require(type != ReminderAlertType.CUSTOM || customAt != null)
    }

    fun token(): String = when (type) {
        ReminderAlertType.CUSTOM -> "CUSTOM@${customAt}"
        else -> type.name
    }

    val key: String get() = token().replace(':', '_')

    companion object {
        fun parse(token: String): ReminderAlert? {
            if (token.startsWith("CUSTOM@")) {
                val at = runCatching { LocalDateTime.parse(token.removePrefix("CUSTOM@")) }.getOrNull()
                return at?.let { ReminderAlert(ReminderAlertType.CUSTOM, it) }
            }
            val type = runCatching { ReminderAlertType.valueOf(token) }.getOrNull() ?: return null
            return ReminderAlert(type)
        }
    }
}

/** Rough part of a day, used to filter "cosa devo fare oggi pomeriggio". */
enum class DayPeriod(val from: LocalTime, val to: LocalTime) {
    MATTINA(LocalTime.of(5, 0), LocalTime.of(12, 0)),
    POMERIGGIO(LocalTime.of(12, 0), LocalTime.of(18, 0)),
    SERA(LocalTime.of(18, 0), LocalTime.of(23, 59)),
    ;

    fun contains(t: LocalTime): Boolean = !t.isBefore(from) && t.isBefore(to)
}

/**
 * One dated item in JARVIS's personal calendar. A non-null [time] makes it an
 * appointment; a null [time] makes it a dated activity/task.
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
    val id: String = stableId(date, time, text),
    val alerts: List<ReminderAlert> = emptyList(),
) {
    /**
     * Obsidian-friendly Markdown line that is also machine-readable:
     * `- [ ] 2026-08-07 15:00 — tagliare i capelli`
     */
    fun toMarkdown(): String {
        val box = if (done) "- [x]" else "- [ ]"
        val stamp = if (time != null) "$date ${"%02d:%02d".format(time.hour, time.minute)}" else "$date"
        val alertTokens = alerts.distinctBy { it.key }.joinToString(",") { it.token() }
        val metadata = "<!-- jarvis:id=$id;alerts=$alertTokens -->"
        return "$box $stamp — $text $metadata"
    }

    companion object {
        private val LINE = Regex("""^-\s*\[( |x|X)]\s*(\d{4}-\d{2}-\d{2})(?:\s+(\d{1,2}):(\d{2}))?\s*[—\-–]\s*(.+)$""")
        private val METADATA = Regex("""\s*<!--\s*jarvis:id=([^;\s]+);alerts=([^\s>]*)\s*-->\s*$""")

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
            val payload = m.groupValues[5].trim()
            val metadata = METADATA.find(payload)
            val text = if (metadata == null) payload else payload.removeRange(metadata.range).trim()
            if (text.isEmpty()) return null
            val id = metadata?.groupValues?.get(1)?.takeIf(String::isNotBlank)
                ?: stableId(date, time, text)
            val alerts = metadata?.groupValues?.get(2).orEmpty()
                .split(',')
                .mapNotNull { ReminderAlert.parse(it.trim()) }
                .distinctBy { it.key }
            return AgendaEntry(
                date = date,
                time = time,
                text = text,
                done = m.groupValues[1].lowercase() == "x",
                id = id,
                alerts = alerts,
            )
        }

        fun stableId(date: LocalDate, time: LocalTime?, text: String): String =
            UUID.nameUUIDFromBytes("$date|${time ?: ""}|${text.trim()}".toByteArray(StandardCharsets.UTF_8))
                .toString()
    }
}
