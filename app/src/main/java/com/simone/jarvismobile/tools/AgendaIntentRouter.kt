package com.simone.jarvismobile.tools

import android.util.Log
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.intent.Action
import com.simone.jarvismobile.core.intent.AgendaCommandParser
import com.simone.jarvismobile.core.intent.Domain
import com.simone.jarvismobile.core.intent.ResolvedIntent
import com.simone.jarvismobile.core.intent.TextNormalizer
import com.simone.jarvismobile.core.protocol.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the planner router made of an utterance. */
sealed interface AgendaRouting {
    /** An unambiguous call, already carrying the resolved entry id. */
    data class Call(val call: ToolCall) : AgendaRouting

    /**
     * Several entries fit. [question] lists them; [candidateIds] and [pending]
     * carry everything needed to finish the job once the user picks one.
     */
    data class Disambiguate(
        val question: String,
        val candidateIds: List<String>,
        val pending: Map<String, String>,
    ) : AgendaRouting

    /** Understood, but nothing in the planner matches. [spoken] says so plainly. */
    data class NotFound(val spoken: String) : AgendaRouting
}

/**
 * Bridges the pure [AgendaCommandParser] to the real planner.
 *
 * The parser says *what* the user wants in their own words; this resolves those
 * words to an actual entry and only then builds a tool call — always by id. The
 * three possible endings are deliberate: exactly one match acts, several ask,
 * none says so. Nothing here writes to the planner; execution stays behind
 * [ToolRunner] and its confirmation policy.
 */
@Singleton
class AgendaIntentRouter @Inject constructor(
    private val agenda: AgendaRepository,
) {

    /**
     * Routes [utterance]. [contextEntryId] is the entry the conversation is
     * already about, used to resolve pronouns ("spostalo alle 16"); pass null
     * when there is no such entry.
     */
    suspend fun route(
        utterance: String,
        contextEntryId: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): AgendaRouting? {
        val intent = AgendaCommandParser.parse(utterance, now) ?: return null
        // Notes and memories have their own tools; this router owns the planner.
        if (intent.domain == Domain.NOTE || intent.domain == Domain.MEMORY) return null
        if (intent.action !in HANDLED) return null
        Log.i(TAG, "intent ${intent.logLine()}")

        val candidates = resolve(intent, contextEntryId)
        return when {
            candidates.isEmpty() -> AgendaRouting.NotFound(notFoundMessage(intent))
            candidates.size == 1 -> AgendaRouting.Call(buildCall(intent, candidates.first()))
            else -> disambiguate(intent, candidates)
        }
    }

    /** Finishes a disambiguation: picks the candidate [reply] refers to. */
    suspend fun resolvePick(
        pending: Map<String, String>,
        candidateIds: List<String>,
        reply: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): AgendaRouting {
        val all = entries().filter { it.id in candidateIds }
        if (all.isEmpty()) return AgendaRouting.NotFound("Quell'impegno non c'è più.")
        val picked = pick(all, reply, now)
            ?: return AgendaRouting.Disambiguate(
                question = "Non ho capito quale. " + listQuestion(all, pending["label"].orEmpty()),
                candidateIds = candidateIds,
                pending = pending,
            )
        return AgendaRouting.Call(callFromPending(pending, picked))
    }

    /**
     * Chooses among [candidates] from a short reply: an ordinal ("il primo"), a
     * day ("quello di giovedì"), an hour ("quello delle 18") or words from the
     * title. Returns null when the reply still fits more than one — never a
     * guess, because the action may be a deletion.
     */
    private fun pick(candidates: List<AgendaEntry>, reply: String, now: LocalDateTime): AgendaEntry? {
        val t = TextNormalizer.normalize(reply)
        if (t.isBlank()) return null

        ORDINALS.entries.firstOrNull { (word, _) ->
            Regex("""(?<!\p{L})$word(?!\p{L})""").containsMatchIn(t)
        }?.let { (_, index) -> candidates.getOrNull(index)?.let { return it } }

        val parsed = com.simone.jarvismobile.core.agenda.ItalianDateTimeParser.parse(reply, now)
        val byDay = parsed.date?.takeIf { parsed.dateExplicit }?.let { d ->
            candidates.filter { it.date == d }
        }.orEmpty()
        if (byDay.size == 1) return byDay.first()

        val byTime = parsed.time?.let { time -> candidates.filter { it.time == time } }.orEmpty()
        if (byTime.size == 1) return byTime.first()

        val byWords = candidates.filter { TextNormalizer.matches(t, it.text) }
        if (byWords.size == 1) return byWords.first()
        return null
    }

    // --- Resolution ----------------------------------------------------

    private suspend fun entries(): List<AgendaEntry> =
        agenda.entries.value.ifEmpty { runCatching { agenda.reload() }.getOrDefault(emptyList()) }

    private suspend fun resolve(intent: ResolvedIntent, contextEntryId: String?): List<AgendaEntry> {
        val open = entries().filterNot { it.done }
        val target = intent.target

        // A bare pronoun with nothing else to go on ("spostalo alle 16") can only
        // mean the entry the conversation is already about.
        if (target.needle.isBlank() && target.date == null && target.time == null) {
            val fromContext = contextEntryId?.let { id -> open.firstOrNull { it.id == id } }
            return listOfNotNull(fromContext)
        }

        val date = target.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val time = target.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        var matches = open.filter { entry ->
            (date == null || entry.date == date) &&
                (time == null || entry.time == time) &&
                (target.needle.isBlank() || TextNormalizer.matches(target.needle, entry.text))
        }

        // With a name but no day, prefer what is still ahead: "sposta il dentista"
        // means the upcoming one, not last month's.
        if (matches.size > 1 && date == null) {
            val today = LocalDate.now()
            val upcoming = matches.filter { it.date == null || !it.date!!.isBefore(today) }
            if (upcoming.isNotEmpty()) matches = upcoming
        }
        return matches
    }

    // --- Call building -------------------------------------------------

    private fun buildCall(intent: ResolvedIntent, entry: AgendaEntry): ToolCall =
        callOf(toolFor(intent), argumentsFor(intent, entry))

    private fun callFromPending(pending: Map<String, String>, entry: AgendaEntry): ToolCall {
        val args = pending.toMutableMap()
        val tool = args.remove("tool") ?: "delete_agenda"
        args["id"] = entry.id
        args["label"] = label(entry)
        return callOf(tool, args)
    }

    private fun callOf(tool: String, args: Map<String, String>): ToolCall = ToolCall(
        id = UUID.randomUUID().toString(),
        name = tool,
        arguments = JsonObject(args.mapValues { JsonPrimitive(it.value) }),
        requiresConfirmation = false,
    )

    private fun toolFor(intent: ResolvedIntent): String = when (intent.action) {
        Action.DELETE -> "delete_agenda"
        Action.MOVE -> "move_agenda"
        else -> "rename_agenda"
    }

    private fun argumentsFor(intent: ResolvedIntent, entry: AgendaEntry): Map<String, String> =
        buildMap {
            put("id", entry.id)
            put("label", label(entry))
            when (intent.action) {
                Action.MOVE -> {
                    intent.changes.date?.let { put("date", it) }
                    intent.changes.time?.let { put("time", it) }
                    intent.changes.shiftMinutes?.let { put("shift_minutes", it.toString()) }
                }
                Action.UPDATE -> intent.changes.title?.let { put("title", it) }
                else -> Unit
            }
        }

    private fun disambiguate(intent: ResolvedIntent, candidates: List<AgendaEntry>): AgendaRouting {
        val shown = candidates.take(MAX_CANDIDATES)
        val pending = argumentsFor(intent, shown.first())
            .filterKeys { it != "id" && it != "label" }
            .toMutableMap()
        pending["tool"] = toolFor(intent)
        pending["label"] = intent.target.needle
        return AgendaRouting.Disambiguate(
            question = listQuestion(shown, intent.target.needle),
            candidateIds = shown.map { it.id },
            pending = pending,
        )
    }

    private fun listQuestion(candidates: List<AgendaEntry>, needle: String): String {
        val what = if (needle.isBlank()) "impegni" else "impegni «$needle»"
        val list = candidates.joinToString(", ") { "${it.text} ${describeWhen(it.date, it.time)}" }
        return "Ho trovato ${candidates.size} $what: $list. Quale?"
    }

    private fun notFoundMessage(intent: ResolvedIntent): String {
        val needle = intent.target.needle
        return if (needle.isBlank()) {
            "Non ho capito a quale impegno ti riferisci."
        } else {
            "Non trovo «$needle» nel calendario personale."
        }
    }

    private fun label(entry: AgendaEntry): String =
        "${entry.text} (${describeWhen(entry.date, entry.time)})"

    private companion object {
        const val TAG = "JarvisIntent"
        const val MAX_CANDIDATES = 5
        val HANDLED = setOf(Action.DELETE, Action.MOVE, Action.UPDATE)
        val ORDINALS = linkedMapOf(
            "primo" to 0, "prima" to 0, "secondo" to 1, "seconda" to 1,
            "terzo" to 2, "terza" to 2, "quarto" to 3, "quinta" to 4,
        )
    }
}
