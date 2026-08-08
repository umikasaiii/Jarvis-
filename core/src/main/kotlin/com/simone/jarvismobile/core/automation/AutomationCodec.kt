package com.simone.jarvismobile.core.automation

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Reads and writes `JARVIS/Automazioni.md`.
 *
 * The file is the source of truth and has to stay readable: a rule reads
 * `- [x] ogni giorno 08:00 — notifica: prendi le vitamine {#a1b2c3d4}`, which is
 * a sentence before it is a record. The id in braces is what survives an edit,
 * so renaming a rule in Obsidian does not create a second one.
 */
object AutomationCodec {

    const val FILE_HEADER = "# Automazioni di JARVIS"

    private val DAY_NAMES = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    private val LINE = Regex("""^\s*-\s*\[( |x|X)]\s*(.+?)\s+—\s+(.+?)\s*$""")
    private val META = Regex("""\{#([A-Za-z0-9_-]+)(?:\s+@(\S+))?}\s*$""")

    private val EVERY_DAY = Regex("""^ogni giorno\s+(\d{1,2}):(\d{2})$""", RegexOption.IGNORE_CASE)
    private val EVERY_DAYS = Regex("""^ogni\s+([a-z,]+)\s+(\d{1,2}):(\d{2})$""", RegexOption.IGNORE_CASE)
    // A one-shot instant is stored as an ISO date plus a clock time, e.g.
    // "una volta 2026-08-08 11:45" — machine-unambiguous, still readable.
    private val ONCE = Regex(
        """^una volta\s+(\d{4}-\d{2}-\d{2})\s+(\d{1,2}):(\d{2})$""",
        RegexOption.IGNORE_CASE,
    )
    private val BATTERY = Regex("""^batteria\s*<\s*(\d{1,2})\s*%$""", RegexOption.IGNORE_CASE)
    private val CHARGING = Regex("""^in\s+carica$""", RegexOption.IGNORE_CASE)

    private val ACTION = Regex("""^(notifica|voce|agenda|comando)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun parseFile(text: String): List<Automation> =
        text.lineSequence().mapNotNull { parseLine(it) }.toList()

    fun renderFile(automations: List<Automation>): String = buildString {
        append(FILE_HEADER).append("\n\n")
        automations.forEach { append(it.toMarkdown()).append('\n') }
    }

    fun parseLine(line: String): Automation? {
        val m = LINE.find(line) ?: return null
        val enabled = m.groupValues[1].equals("x", ignoreCase = true)
        val triggerText = m.groupValues[2].trim()

        // The metadata lives at the end of the action half; strip it first so the
        // message itself never keeps a stray brace.
        var actionText = m.groupValues[3].trim()
        val meta = META.find(actionText)
        val id = meta?.groupValues?.get(1) ?: Automation.newId()
        val lastFired = meta?.groupValues?.get(2)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        if (meta != null) actionText = actionText.removeRange(meta.range).trim()

        val trigger = parseTrigger(triggerText) ?: return null
        val action = parseAction(actionText) ?: return null
        return Automation(
            id = id,
            name = action.payload.take(NAME_CHARS),
            enabled = enabled,
            trigger = trigger,
            action = action,
            lastFired = lastFired,
        )
    }

    // --- triggers ----------------------------------------------------------

    fun parseTrigger(text: String): Trigger? {
        val t = text.trim()
        EVERY_DAY.find(t)?.let { m ->
            val time = time(m.groupValues[1], m.groupValues[2]) ?: return null
            return Trigger.TimeOfDay(time)
        }
        EVERY_DAYS.find(t)?.let { m ->
            val days = m.groupValues[1].split(',')
                .mapNotNull { d -> DAY_NAMES.indexOf(d.trim().lowercase()).takeIf { it >= 0 } }
                .map { DayOfWeek.of(it + 1) }
                .toSet()
            if (days.isEmpty()) return null
            val time = time(m.groupValues[2], m.groupValues[3]) ?: return null
            return Trigger.TimeOfDay(time, days)
        }
        ONCE.find(t)?.let { m ->
            val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return null
            val time = time(m.groupValues[2], m.groupValues[3]) ?: return null
            return Trigger.Once(LocalDateTime.of(date, time))
        }
        BATTERY.find(t)?.let { m ->
            val percent = m.groupValues[1].toIntOrNull() ?: return null
            return if (percent in 1..99) Trigger.BatteryBelow(percent) else null
        }
        if (CHARGING.matches(t)) return Trigger.ChargingStarted
        return null
    }

    fun renderTrigger(trigger: Trigger): String = when (trigger) {
        is Trigger.TimeOfDay -> {
            val clock = "%02d:%02d".format(trigger.time.hour, trigger.time.minute)
            if (trigger.days.isEmpty()) {
                "ogni giorno $clock"
            } else {
                val days = trigger.days.sortedBy { it.value }.joinToString(",") { DAY_NAMES[it.value - 1] }
                "ogni $days $clock"
            }
        }
        is Trigger.Once -> {
            val date = trigger.at.toLocalDate()
            val clock = "%02d:%02d".format(trigger.at.hour, trigger.at.minute)
            "una volta $date $clock"
        }
        is Trigger.BatteryBelow -> "batteria < ${trigger.percent}%"
        Trigger.ChargingStarted -> "in carica"
    }

    // --- actions -----------------------------------------------------------

    fun parseAction(text: String): Action? {
        val m = ACTION.find(text.trim()) ?: return null
        val body = m.groupValues[2].trim()
        if (body.isEmpty()) return null
        return when (m.groupValues[1].lowercase()) {
            "notifica" -> Action.Notify(body)
            "voce" -> Action.Speak(body)
            "agenda" -> Action.AddAgenda(body)
            "comando" -> Action.Tool(body)
            else -> null
        }
    }

    fun renderAction(action: Action): String = when (action) {
        is Action.Notify -> "notifica: ${action.payload}"
        is Action.Speak -> "voce: ${action.payload}"
        is Action.AddAgenda -> "agenda: ${action.payload}"
        is Action.Tool -> "comando: ${action.payload}"
    }

    /** How the rule is described in the UI and read back to the user. */
    fun describe(automation: Automation): String =
        "${renderTrigger(automation.trigger)} → ${renderAction(automation.action)}"

    private fun time(hour: String, minute: String): LocalTime? {
        val h = hour.toIntOrNull() ?: return null
        val m = minute.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    private const val NAME_CHARS = 60
}
