package com.simone.jarvismobile.engine

import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.core.memory.MemoryEntry
import com.simone.jarvismobile.core.memory.MemoryTier
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.core.tools.ToolFamily
import com.simone.jarvismobile.tools.ToolOutcome
import com.simone.jarvismobile.util.runCancellable
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

    /**
     * § FASE 2A.8 RELEASE GATE A — real bug audited: "Che impegni ho domani?"
     * → "E dopodomani?" used to reach the model with NO family selected at
     * all (`matchedFamilies` finds no keyword in a bare date phrase), so the
     * FAST prompt explicitly told it "no tool needed" and it answered from
     * nothing. This is deliberately separate from [PendingTask] above (which
     * only tracks an in-flight AGENDA WRITE, e.g. "Ricordami... " → "Anzi,
     * alle 18") — a plain data-QUERY follow-up ("E dopodomani?" after a
     * read-only "che impegni ho domani?") never creates or touches a
     * `PendingTask` at all, so without this it has nothing to resolve
     * against. Short idle timeout: a "the next bare date word means the same
     * capability" assumption should not survive an unrelated topic switch a
     * few minutes later.
     */
    private data class LastCapabilityTopic(val family: ToolFamily, val touchedAtMs: Long)

    @Volatile private var lastCapabilityTopic: LastCapabilityTopic? = null

    /** The [ToolFamily] a capability request most recently, successfully resolved to — or null if none/gone stale. */
    fun currentCapabilityTopic(): ToolFamily? {
        val t = lastCapabilityTopic ?: return null
        if (System.currentTimeMillis() - t.touchedAtMs > TOPIC_IDLE_TIMEOUT_MS) {
            lastCapabilityTopic = null
            return null
        }
        return t.family
    }

    fun noteCapabilityTopic(family: ToolFamily) {
        lastCapabilityTopic = LastCapabilityTopic(family, System.currentTimeMillis())
    }

    /**
     * § FASE 2A.8 RELEASE GATE A/C — the RAM/VRAM anaphora case: "Che
     * differenza c'è tra RAM e VRAM?" (a KNOWLEDGE question, answered by the
     * model, never a tool) is remembered here so "Quanta ne ho nel telefono?"
     * (a bare partitive follow-up with no metric noun of its own — see
     * [com.simone.jarvismobile.core.tools.DeviceInfoFollowUp]) can resolve to
     * the right [GetDeviceInfoTool][com.simone.jarvismobile.tools.GetDeviceInfoTool]
     * metric instead of reaching the model with nothing to answer either.
     * [topic] is a plain device-metric noun string (e.g. `"ram"`), never
     * personal content.
     */
    private data class LastKnowledgeTopic(val topic: String, val touchedAtMs: Long)

    @Volatile private var lastKnowledgeTopic: LastKnowledgeTopic? = null

    fun currentKnowledgeTopic(): String? {
        val t = lastKnowledgeTopic ?: return null
        if (System.currentTimeMillis() - t.touchedAtMs > TOPIC_IDLE_TIMEOUT_MS) {
            lastKnowledgeTopic = null
            return null
        }
        return t.topic
    }

    fun noteKnowledgeTopic(topic: String) {
        lastKnowledgeTopic = LastKnowledgeTopic(topic, System.currentTimeMillis())
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
        runCancellable { memoryEngine.storeEpisodic(task.toMemoryEntry()) }
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

        // § FASE 2A.8 — deliberately shorter than IDLE_TIMEOUT_MS above: a
        // bare "E dopodomani?"/"Quanta ne ho?" follow-up is a much tighter
        // conversational move than a slow correction to an agenda write, so
        // an old topic should stop being assumed sooner.
        const val TOPIC_IDLE_TIMEOUT_MS = 3 * 60 * 1000L

        val AGENDA_ENTRY_TOOLS = setOf(
            "add_reminder", "add_task", "move_agenda", "rename_agenda",
            "update_agenda_notes", "complete_agenda",
        )
    }
}
