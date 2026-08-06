package com.simone.jarvismobile.tools

import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.DayPeriod
import com.simone.jarvismobile.core.tools.SensitivityLevel
import com.simone.jarvismobile.core.tools.Tool
import com.simone.jarvismobile.core.tools.ToolPolicy
import com.simone.jarvismobile.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private fun okJson(vararg pairs: Pair<String, String>): ToolResult =
    ToolResult.Success(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))

private fun JsonObject.text(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Files a commitment in JARVIS's calendar with a real date (and, if the user said
 * one, a real time) — not a free-text note with "domani" inside the description.
 * Storing the date as data is what will later let a reminder actually fire.
 */
class AddReminderTool(private val agenda: AgendaRepository) : Tool {
    override val name = "add_reminder"
    override val description = "Aggiunge un impegno all'agenda con data e, se indicata, ora."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? {
        val text = arguments.text("text") ?: return "manca il campo 'text'"
        if (text.length < 2) return "testo troppo corto"
        val date = arguments.text("date") ?: return "manca il campo 'date'"
        runCatching { LocalDate.parse(date) }.getOrNull() ?: return "data non valida"
        arguments.text("time")?.let {
            runCatching { LocalTime.parse(it) }.getOrNull() ?: return "ora non valida"
        }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments.text("text") ?: return ToolResult.Failure("missing_text")
        val date = arguments.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return ToolResult.Failure("missing_date")
        val time = arguments.text("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

        val entry = AgendaEntry(date = date, time = time, text = text)
        val saved = runCatching { agenda.add(entry) }.getOrDefault(false)
        if (!saved) return ToolResult.Failure("agenda_write_failed")

        val today = LocalDate.now()
        val whenSaid = Agenda.humanDate(date, today) +
            (time?.let { " alle ${Agenda.humanTime(it)}" } ?: "")
        return okJson(
            "date" to date.toString(),
            "time" to (time?.toString() ?: ""),
            "text" to text,
            "spoken" to "Segnato: $text, $whenSaid.",
        )
    }
}

/**
 * Reads the agenda back. Deterministic on purpose: what is on the calendar comes
 * from the file, never from the model, so an appointment can't be invented.
 */
class ListAgendaTool(private val agenda: AgendaRepository) : Tool {
    override val name = "list_agenda"
    override val description = "Elenca gli impegni in agenda, eventualmente di un giorno o di una parte della giornata."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        arguments.text("day")?.let {
            runCatching { LocalDate.parse(it) }.getOrNull() ?: return "giorno non valido"
        }
        arguments.text("period")?.let { p ->
            if (DayPeriod.entries.none { it.name.equals(p, ignoreCase = true) }) return "periodo non valido"
        }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val today = LocalDate.now()
        val day = arguments.text("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val period = arguments.text("period")
            ?.let { p -> DayPeriod.entries.firstOrNull { it.name.equals(p, ignoreCase = true) } }

        val items = runCatching { agenda.query(today, day, period) }.getOrDefault(emptyList())
        val scope = buildString {
            if (day != null) append(" per ").append(Agenda.humanDate(day, today))
            if (period != null) append(if (day != null) " " else " per ").append(period.name.lowercase())
        }

        val spoken = when {
            items.isEmpty() -> "Non hai impegni in agenda$scope."
            items.size == 1 -> "Hai un impegno$scope: ${Agenda.speak(items.first(), today)}."
            else -> "Hai ${items.size} impegni$scope: " +
                items.joinToString("; ") { Agenda.speak(it, today) } + "."
        }
        return okJson("count" to items.size.toString(), "spoken" to spoken)
    }
}

/**
 * Answers "quanto manca alle 16?" by subtracting clock times, not by asking the
 * model. A 3B model once answered "2 ore e 45 minuti" for the gap between 08:03
 * and 16:00; arithmetic on time belongs in code.
 */
class TimeUntilTool : Tool {
    override val name = "time_until"
    override val description = "Dice quanto manca a un orario o a una data."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 500L

    override fun validate(arguments: JsonObject): String? {
        val h = arguments.text("hour")?.toIntOrNull()
        val date = arguments.text("date")
        if (h == null && date == null) return "manca l'orario o la data"
        if (h != null && h !in 0..23) return "ora non valida"
        val m = arguments.text("minute")?.toIntOrNull() ?: 0
        if (m !in 0..59) return "minuti non validi"
        if (date != null && runCatching { LocalDate.parse(date) }.getOrNull() == null) return "data non valida"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val now = LocalDateTime.now()
        val hour = arguments.text("hour")?.toIntOrNull()
        val minute = arguments.text("minute")?.toIntOrNull() ?: 0
        val date = arguments.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        if (hour == null) {
            val d = date ?: return ToolResult.Failure("missing_target")
            val days = Duration.between(now.toLocalDate().atStartOfDay(), d.atStartOfDay()).toDays()
            val spoken = when {
                days < 0 -> "${Agenda.humanDate(d, now.toLocalDate())} è già passato."
                days == 0L -> "È oggi."
                days == 1L -> "Manca un giorno."
                else -> "Mancano $days giorni."
            }
            return okJson("days" to days.toString(), "spoken" to spoken)
        }

        val target = (date ?: now.toLocalDate()).atTime(hour, minute)
        val fixed = if (date == null && target.isBefore(now)) target.plusDays(1) else target
        val minutes = Duration.between(now, fixed).toMinutes()
        val clock = "%02d:%02d".format(hour, minute)
        val spoken = if (minutes < 0) {
            "Le $clock sono già passate."
        } else {
            "Mancano ${Agenda.humanMinutes(minutes)} alle $clock."
        }
        return okJson("minutes" to minutes.toString(), "target" to clock, "spoken" to spoken)
    }
}
