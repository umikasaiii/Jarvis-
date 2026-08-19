package com.simone.jarvismobile.tools

import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.core.tools.SensitivityLevel
import com.simone.jarvismobile.core.tools.Tool
import com.simone.jarvismobile.core.tools.ToolPolicy
import com.simone.jarvismobile.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Query / delete / move / rename for the personal planner.
 *
 * Every tool here addresses its entry by **id**, never by words. Resolving the
 * user's phrasing to an actual entry — and asking which one they meant when more
 * than one fits — happens earlier, in [AgendaIntentRouter]. By the time a call
 * reaches these tools the target is already unambiguous, so a vague "cancella la
 * riunione" can never delete the wrong item: it either arrives with one id or it
 * never becomes a call at all (docs/SECURITY.md §15).
 */

private fun okJson(vararg pairs: Pair<String, String>): ToolResult =
    ToolResult.Success(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))

private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

/** Human-readable "giovedì 6 agosto alle 18:00" for spoken confirmations. */
internal fun describeWhen(date: LocalDate?, time: LocalTime?): String {
    if (date == null) return "senza data"
    val day = "%s %d %s".format(
        DAY_NAMES[date.dayOfWeek.value - 1],
        date.dayOfMonth,
        MONTH_NAMES[date.monthValue - 1],
    )
    return if (time == null) day else "$day alle ${"%02d:%02d".format(time.hour, time.minute)}"
}

private val DAY_NAMES = listOf(
    "lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica",
)
private val MONTH_NAMES = listOf(
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
)

/**
 * Answers "when is X" for one already-resolved planner entry — read-only, so it
 * needs no confirmation. [AgendaIntentRouter] does the actual name lookup (and
 * the disambiguation question when more than one entry fits); this tool only
 * speaks the date/time of the one id it is given.
 */
class QueryAgendaTool(private val agenda: AgendaRepository) : Tool {
    override val name = "query_agenda"
    override val description = "Dice quando è previsto un impegno o un'attività del calendario personale."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.str("id") == null) "manca il campo 'id'" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val id = arguments.str("id") ?: return ToolResult.Failure("missing_id")
        val entry = agenda.entries.value.firstOrNull { it.id == id }
            ?: return ToolResult.Failure("agenda_item_not_found")
        return okJson(
            "id" to entry.id,
            "spoken" to "${entry.text}: ${describeWhen(entry.date, entry.time)}.",
        )
    }
}

/**
 * Removes one planner entry. Deleting cannot be undone, so this is a confirming
 * write: the user always sees exactly which entry is about to go.
 */
class DeleteAgendaTool(private val agenda: AgendaRepository) : Tool {
    override val name = "delete_agenda"
    override val description = "Elimina un impegno o un'attività dal calendario personale."
    override val policy = ToolPolicy.CONFIRMING_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.str("id") == null) "manca il campo 'id'" else null

    override fun confirmationPrompt(arguments: JsonObject): String? {
        val label = arguments.str("label") ?: return "Confermi di eliminare questo impegno?"
        return "Confermi di eliminare “$label”?"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val id = arguments.str("id") ?: return ToolResult.Failure("missing_id")
        val label = arguments.str("label") ?: "l'impegno"
        val removed = runCatching { agenda.removeById(id) }.getOrDefault(false)
        return if (removed) {
            okJson("id" to id, "spoken" to "Eliminato: $label.")
        } else {
            ToolResult.Failure("agenda_item_not_found")
        }
    }
}

/**
 * Moves an entry to a new date/time, either absolutely ("a venerdì", "alle 17")
 * or relatively ("di due ore", "anticipalo di mezz'ora"). Reversible, so it is a
 * low-risk write rather than a confirming one — but it always says what it did,
 * with the new day and hour, so a wrong move is obvious immediately.
 */
class MoveAgendaTool(private val agenda: AgendaRepository) : Tool {
    override val name = "move_agenda"
    override val description = "Sposta un impegno a una nuova data o a un nuovo orario."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        if (arguments.str("id") == null) return "manca il campo 'id'"
        val hasAbsolute = arguments.str("date") != null || arguments.str("time") != null
        val hasShift = arguments.str("shift_minutes")?.toLongOrNull() != null
        if (!hasAbsolute && !hasShift) return "manca la nuova data o il nuovo orario"
        arguments.str("date")?.let {
            if (runCatching { LocalDate.parse(it) }.isFailure) return "data non valida"
        }
        arguments.str("time")?.let {
            if (runCatching { LocalTime.parse(it) }.isFailure) return "orario non valido"
        }
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val id = arguments.str("id") ?: return ToolResult.Failure("missing_id")
        val newDate = arguments.str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val newTime = arguments.str("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val shift = arguments.str("shift_minutes")?.toLongOrNull()

        val updated = runCatching {
            agenda.update(id) { entry ->
                if (shift != null) {
                    // A relative shift needs a real instant to move: an undated
                    // task has nothing to add hours to, so it is left untouched
                    // and reported below rather than silently given today's date.
                    val base = entry.date?.atTime(entry.time ?: LocalTime.MIDNIGHT)
                    if (base == null) {
                        entry
                    } else {
                        val moved: LocalDateTime = base.plusMinutes(shift)
                        entry.copy(
                            date = moved.toLocalDate(),
                            time = if (entry.time == null) null else moved.toLocalTime(),
                        )
                    }
                } else {
                    entry.copy(
                        date = newDate ?: entry.date,
                        time = newTime ?: entry.time,
                    )
                }
            }
        }.getOrNull() ?: return ToolResult.Failure("agenda_item_not_found")

        if (shift != null && updated.date == null) {
            return ToolResult.Failure("agenda_undated_shift")
        }
        return okJson(
            "id" to updated.id,
            "spoken" to "Spostato: ${updated.text}, ${describeWhen(updated.date, updated.time)}.",
        )
    }
}

/** Renames an entry, keeping its id, date, alerts and list untouched. */
class RenameAgendaTool(private val agenda: AgendaRepository) : Tool {
    override val name = "rename_agenda"
    override val description = "Cambia il titolo di un impegno del calendario personale."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        if (arguments.str("id") == null) return "manca il campo 'id'"
        val title = arguments.str("title") ?: return "manca il nuovo titolo"
        if (title.length < 2) return "titolo troppo corto"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val id = arguments.str("id") ?: return ToolResult.Failure("missing_id")
        val title = arguments.str("title") ?: return ToolResult.Failure("missing_title")
        val updated = runCatching { agenda.update(id) { it.copy(text = title.trim()) } }.getOrNull()
            ?: return ToolResult.Failure("agenda_item_not_found")
        return okJson("id" to updated.id, "spoken" to "Rinominato in: ${updated.text}.")
    }
}

/**
 * Replaces the free-text notes of an entry, keeping its id, title, date and
 * alerts untouched. Overwrite semantics, same as [RenameAgendaTool] for
 * `text` — the caller (`JarvisBrain`, given the entry's current notes in its
 * context) composes the full new value, e.g. appending "carta vetrata" to
 * what was already there; this tool does not itself merge or append.
 *
 * Added for the Conversational AI engine's multi-turn example ("Ricordami
 * domani di comprare il fissativo" → "anzi, alle 18" → "e aggiungi anche la
 * carta vetrata"): `move_agenda`/`rename_agenda` already existed for the
 * first two turns, but nothing could write [com.simone.jarvismobile.core.agenda.AgendaEntry.notes]
 * without mangling the title — this is the one genuinely new tool that gap
 * needed, and it goes through the same shared `ToolRegistry`, so Classic
 * mode gains it too rather than the engines diverging on capability.
 */
class UpdateAgendaNotesTool(private val agenda: AgendaRepository) : Tool {
    override val name = "update_agenda_notes"
    override val description = "Aggiorna le note libere di un impegno o un'attività del calendario personale."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? {
        if (arguments.str("id") == null) return "manca il campo 'id'"
        if (arguments.str("notes") == null) return "mancano le nuove note"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val id = arguments.str("id") ?: return ToolResult.Failure("missing_id")
        val notes = arguments.str("notes") ?: return ToolResult.Failure("missing_notes")
        val updated = runCatching { agenda.update(id) { it.copy(notes = notes.trim()) } }.getOrNull()
            ?: return ToolResult.Failure("agenda_item_not_found")
        return okJson("id" to updated.id, "spoken" to "Note aggiornate per: ${updated.text}.")
    }
}
