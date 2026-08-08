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
 * One item in JARVIS's unified personal planner — a Google-Tasks-style task that
 * is *also* a calendar entry when it carries a date/time.
 *
 * A non-null [time] makes it an appointment; a [date] with no time is a dated
 * task; a null [date] is an undated task that simply lives in its [list] (Google
 * Tasks allows tasks with no due date). On top of the date it carries the task
 * fields the reference app has: a [list] it belongs to, a [starred] flag, free
 * [notes], an optional [parentId] for one level of sub-tasks, a manual [order],
 * and a [completedAt] date for the "Completate" section.
 *
 * Everything is stored as one Obsidian-friendly Markdown line so the vault stays
 * the human-readable source of truth. New fields live in the trailing metadata
 * comment and default to empty, so a file written by an older build still reads
 * back unchanged.
 */
data class AgendaEntry(
    val date: LocalDate?,
    val time: LocalTime? = null,
    val text: String,
    val done: Boolean = false,
    val id: String = stableId(date, time, text),
    val alerts: List<ReminderAlert> = emptyList(),
    val list: String = DEFAULT_LIST,
    val starred: Boolean = false,
    val notes: String = "",
    val parentId: String? = null,
    val order: Int = 0,
    val completedAt: LocalDate? = null,
) {
    /** A task with no due date — it lives only in its list, like a Google Task. */
    val undated: Boolean get() = date == null

    /**
     * Obsidian-friendly Markdown line that is also machine-readable:
     * `- [ ] 2026-08-07 15:00 — tagliare i capelli <!-- jarvis:id=…;alerts=… -->`
     * An undated task drops the date stamp: `- [ ] — comprare il pane <!-- … -->`.
     */
    fun toMarkdown(): String {
        val box = if (done) "- [x]" else "- [ ]"
        val stamp = when {
            date != null && time != null -> "$date ${"%02d:%02d".format(time.hour, time.minute)}"
            date != null -> "$date"
            else -> ""
        }
        val alertTokens = alerts.distinctBy { it.key }.joinToString(",") { it.token() }
        val meta = buildString {
            append("<!-- jarvis:id=").append(id)
            append(";alerts=").append(alertTokens)
            // Only non-default task fields are written, so a plain reminder stays
            // a plain one-liner and diffs stay small.
            if (list != DEFAULT_LIST) append(";list=").append(sanitize(list))
            if (starred) append(";star=1")
            if (notes.isNotBlank()) append(";notes=").append(sanitize(notes))
            parentId?.let { append(";parent=").append(it) }
            if (order != 0) append(";order=").append(order)
            completedAt?.let { append(";done=").append(it) }
            append(" -->")
        }
        val prefix = if (stamp.isEmpty()) "$box —" else "$box $stamp —"
        return "$prefix $text $meta"
    }

    companion object {
        const val DEFAULT_LIST = "Personale"

        // The date/time is optional now: an undated task has no stamp before the
        // dash. The first alternative keeps a stamp, the second matches a bare
        // "- [ ] — text".
        private val LINE = Regex(
            """^-\s*\[( |x|X)]\s*(?:(\d{4}-\d{2}-\d{2})(?:\s+(\d{1,2}):(\d{2}))?\s*)?[—\-–]\s*(.+)$""",
        )
        private val METADATA = Regex("""\s*<!--\s*jarvis:([^>]*?)\s*-->\s*$""")

        /** Parses a line produced by [toMarkdown]; null when it isn't one. */
        fun parse(line: String): AgendaEntry? {
            val m = LINE.find(line.trim()) ?: return null
            val date = m.groupValues[2].takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val h = m.groupValues[3].toIntOrNull()
            val min = m.groupValues[4].toIntOrNull()
            val time = if (h != null && min != null && h in 0..23 && min in 0..59) {
                LocalTime.of(h, min)
            } else {
                null
            }
            val payload = m.groupValues[5].trim()
            val metaMatch = METADATA.find(payload)
            val text = if (metaMatch == null) payload else payload.removeRange(metaMatch.range).trim()
            if (text.isEmpty()) return null

            val fields = metaMatch?.groupValues?.get(1).orEmpty()
                .split(';')
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1)
                }
                .toMap()

            val id = fields["id"]?.takeIf(String::isNotBlank) ?: stableId(date, time, text)
            val alerts = fields["alerts"].orEmpty()
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
                list = fields["list"]?.takeIf { it.isNotBlank() } ?: DEFAULT_LIST,
                starred = fields["star"] == "1",
                notes = fields["notes"].orEmpty(),
                parentId = fields["parent"]?.takeIf { it.isNotBlank() },
                order = fields["order"]?.toIntOrNull() ?: 0,
                completedAt = fields["done"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
        }

        fun stableId(date: LocalDate?, time: LocalTime?, text: String): String =
            UUID.nameUUIDFromBytes("${date ?: ""}|${time ?: ""}|${text.trim()}".toByteArray(StandardCharsets.UTF_8))
                .toString()

        /** Keeps a metadata value on one line and clear of the delimiters. */
        private fun sanitize(value: String): String = value
            .replace('\n', ' ').replace('\r', ' ')
            .replace(";", ",").replace("-->", "→")
            .trim()
    }
}
