package com.simone.jarvismobile.tools

import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.DayPeriod
import com.simone.jarvismobile.core.agenda.ReminderAlert
import com.simone.jarvismobile.core.agenda.ReminderAlertType
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

        // A reminder is a promise to be told: attach a notification by default,
        // so the user never has to ask for it. An appointment fires at its hour;
        // a dated task with no hour fires the morning of. The user can remove the
        // alert by hand from the agenda if they don't want it.
        val defaultAlert = ReminderAlert(
            if (time != null) ReminderAlertType.AT_TIME else ReminderAlertType.MORNING_OF,
        )
        val entry = AgendaEntry(date = date, time = time, text = text, alerts = listOf(defaultAlert))
        val saved = runCatching { agenda.add(entry) }.getOrDefault(false)
        if (!saved) return ToolResult.Failure("agenda_write_failed")

        val today = LocalDate.now()
        val whenSaid = Agenda.humanDate(date, today) +
            (time?.let { " alle ${Agenda.humanTime(it)}" } ?: "")
        return okJson(
            "id" to entry.id,
            "date" to date.toString(),
            "time" to (time?.toString() ?: ""),
            "text" to text,
            "spoken" to "Segnato: $text, $whenSaid.",
        )
    }
}

/**
 * Creates a Google-Tasks-style task: a title, an OPTIONAL due date/time, the
 * list it belongs to, and whether it is starred. Unlike [AddReminderTool] the
 * date is optional, so "aggiungi alla lista Lavoro comprare il pane" files an
 * undated task. Arguments always come from the user's own words (the matcher),
 * never invented by the model.
 */
class AddTaskTool(private val agenda: AgendaRepository) : Tool {
    override val name = "add_task"
    override val description = "Aggiunge un'attività (con lista, stella e scadenza opzionali)."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? {
        val text = arguments.text("text") ?: return "manca il campo 'text'"
        if (text.length < 2) return "testo troppo corto"
        arguments.text("date")?.let {
            runCatching { LocalDate.parse(it) }.getOrNull() ?: return "data non valida"
        }
        arguments.text("time")?.let {
            runCatching { LocalTime.parse(it) }.getOrNull() ?: return "ora non valida"
        }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments.text("text") ?: return ToolResult.Failure("missing_text")
        val date = arguments.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val time = arguments.text("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val list = arguments.text("list") ?: AgendaEntry.DEFAULT_LIST
        val starred = arguments.text("starred") == "true"

        // A dated task notifies by default (at its hour, or the morning of),
        // like a reminder; an undated Google-Tasks item has nothing to fire on.
        val alerts = if (date != null) {
            listOf(ReminderAlert(if (time != null) ReminderAlertType.AT_TIME else ReminderAlertType.MORNING_OF))
        } else {
            emptyList()
        }
        val entry = AgendaEntry(date = date, time = time, text = text, list = list, starred = starred, alerts = alerts)
        val saved = runCatching { agenda.add(entry) }.getOrDefault(false)
        if (!saved) return ToolResult.Failure("agenda_write_failed")

        val today = LocalDate.now()
        val where = if (list == AgendaEntry.DEFAULT_LIST) "" else " nella lista $list"
        val whenSaid = date?.let {
            ", " + Agenda.humanDate(it, today) + (time?.let { t -> " alle ${Agenda.humanTime(t)}" } ?: "")
        } ?: ""
        val star = if (starred) " ⭐" else ""
        return okJson(
            "id" to entry.id,
            "text" to text,
            "list" to list,
            "starred" to starred.toString(),
            "date" to (date?.toString() ?: ""),
            "time" to (time?.toString() ?: ""),
            "spoken" to "Aggiunta$star: $text$where$whenSaid.",
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
        // § FASE 2A.9 — an inclusive range end, e.g. for "tutta la settimana
        // prossima"; ignored by execute() unless "day" (the range start) is
        // also present, exactly like Agenda.filter's own contract.
        arguments.text("to")?.let {
            runCatching { LocalDate.parse(it) }.getOrNull() ?: return "data finale non valida"
        }
        arguments.text("period")?.let { p ->
            if (DayPeriod.entries.none { it.name.equals(p, ignoreCase = true) }) return "periodo non valido"
        }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val today = LocalDate.now()
        val day = arguments.text("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val to = arguments.text("to")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val period = arguments.text("period")
            ?.let { p -> DayPeriod.entries.firstOrNull { it.name.equals(p, ignoreCase = true) } }

        val items = runCatching { agenda.query(today, day, period, toDay = to) }.getOrDefault(emptyList())
        val scope = buildString {
            if (day != null && to != null) {
                append(" dal ").append(Agenda.humanDate(day, today)).append(" al ").append(Agenda.humanDate(to, today))
            } else if (day != null) {
                append(" per ").append(Agenda.humanDate(day, today))
            }
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
 * Completes an existing task straight away. Ticking an item off is a low-risk,
 * fully reversible write (it can be re-opened), and [AgendaRepository.markDone]
 * already fails closed on ambiguity — so "segna X come completato" is honoured at
 * once, without a second confirmation the user found redundant.
 */
/**
 * Marks one planner entry done, addressed by **id** — never by words. Like
 * [DeleteAgendaTool]/[MoveAgendaTool]/[RenameAgendaTool], resolving the user's
 * phrasing to an actual entry (and asking which one when more than one fits)
 * happens earlier, in [AgendaIntentRouter]; by the time a call reaches this
 * tool the target is already unambiguous.
 */
class CompleteAgendaTool(private val agenda: AgendaRepository) : Tool {
    override val name = "complete_agenda"
    override val description = "Segna come completata un'attività del calendario personale."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.text("id") == null) "manca il campo 'id'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val id = arguments.text("id") ?: return ToolResult.Failure("missing_id")
        val completed = runCatching { agenda.setDone(id, true) }.getOrNull()
            ?: return ToolResult.Failure("agenda_item_not_found")
        return okJson(
            "id" to completed.id,
            "spoken" to "Completata: ${completed.text}.",
        )
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
