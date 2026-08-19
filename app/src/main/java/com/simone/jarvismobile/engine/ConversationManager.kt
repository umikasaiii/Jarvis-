package com.simone.jarvismobile.engine

import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.core.memory.MemoryEntry
import com.simone.jarvismobile.core.memory.MemoryTier
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.tools.ToolOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** One in-flight agenda entry the conversation is still talking about. */
data class PendingTask(
    val entryId: String,
    val label: String,
    val date: String?,
    val time: String?,
    val notes: String,
    val lastTouchedAt: Long,
)

/**
 * Tracks the current-task state a multi-turn exchange needs, so a correction
 * or an addition that doesn't re-name the entry still lands on the right one
 * (spec §8's example: "Ricordami domani di comprare il fissativo" → "Anzi,
 * alle 18" → "E aggiungi anche la carta vetrata" — all three resolve to the
 * same reminder). `ConversationalJarvisEngine` calls [onToolExecuted] after
 * EVERY tool call it runs (fast-path or brain-issued alike), and feeds
 * [snapshotText] into `ContextAssembler` for the next turn.
 *
 * Deliberately independent of `SessionCoordinator`'s own `lastAgendaEntryId`:
 * that field is set only on edits to an EXISTING entry (delete/move/rename/
 * complete/query — see `AgendaIntentRouter`), never on creation, so Classic
 * mode today cannot actually do the fissativo example either — this fills a
 * real, previously-unfilled gap rather than duplicating existing state.
 *
 * State is in-memory only (`@Volatile`, matching `ProModeCoordinator`'s own
 * `pendingConfirmation` field) plus a best-effort mirror into the Episodic
 * memory tier for retrieval — it does not attempt to resume a pending task
 * across a process restart, the same honest limitation
 * `SessionCoordinator`'s own `pending*` fields already have today.
 */
@Singleton
class ConversationManager @Inject constructor(
    private val agenda: AgendaRepository,
    private val memoryEngine: MemoryEngine,
) {
    @Volatile private var pending: PendingTask? = null

    /** The current pending task, or null if there is none or it has gone stale. */
    fun current(): PendingTask? {
        val p = pending ?: return null
        if (System.currentTimeMillis() - p.lastTouchedAt > IDLE_TIMEOUT_MS) {
            pending = null
            return null
        }
        return p
    }

    /** What `ContextAssembler` includes in the prompt for the next turn. */
    fun snapshotText(): String? {
        val p = current() ?: return null
        return buildString {
            append("id=").append(p.entryId)
            append(", titolo=\"").append(p.label).append('"')
            if (p.date != null) append(", data=").append(p.date)
            if (p.time != null) append(", ora=").append(p.time)
            if (p.notes.isNotBlank()) append(", note=\"").append(p.notes).append('"')
        }
    }

    /** Clears the pending task — an unrelated high-confidence request, or a fresh topic. */
    fun clear() {
        pending = null
    }

    /**
     * Called after every tool execution in a conversational turn. Only agenda
     * writes that create or touch a single entry update the pending task;
     * anything else (a read-only tool, a non-agenda tool) leaves it untouched
     * so an unrelated aside in the middle of a correction doesn't lose it.
     */
    suspend fun onToolExecuted(call: ToolCall, outcome: ToolOutcome) {
        if (outcome !is ToolOutcome.Done) return
        if (call.name !in AGENDA_ENTRY_TOOLS) return

        val id = when (call.name) {
            "add_reminder", "add_task" -> outcome.raw.text("id")
            else -> call.arguments.text("id")
        } ?: return

        val entry = runCatching { agenda.entries.value.firstOrNull { it.id == id } }.getOrNull() ?: return
        val task = PendingTask(
            entryId = entry.id,
            label = entry.text,
            date = entry.date?.toString(),
            time = entry.time?.toString(),
            notes = entry.notes,
            lastTouchedAt = System.currentTimeMillis(),
        )
        pending = task
        runCatching { memoryEngine.storeEpisodic(task.toMemoryEntry()) }
    }

    private fun PendingTask.toMemoryEntry(): MemoryEntry = MemoryEntry(
        id = "pending_task-$entryId",
        content = "Operazione in corso su \"$label\"" + (date?.let { " ($it${time?.let { t -> " $t" } ?: ""})" } ?: ""),
        type = "pending_task",
        tier = MemoryTier.EPISODIC,
        timestamp = lastTouchedAt,
        importance = 0.8,
        source = "conversation",
        lastAccessed = lastTouchedAt,
    )

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        // Long enough that a slow follow-up ("anzi, alle 18") a minute later
        // still lands on the same entry; short enough that a stale pending
        // task from an hour-old conversation never resurfaces unexpectedly.
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        val AGENDA_ENTRY_TOOLS = setOf(
            "add_reminder", "add_task", "move_agenda", "rename_agenda",
            "update_agenda_notes", "complete_agenda",
        )
    }
}
