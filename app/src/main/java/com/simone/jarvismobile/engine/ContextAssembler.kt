package com.simone.jarvismobile.engine

import android.util.Log
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.snapshot.ContextBudget
import com.simone.jarvismobile.core.snapshot.RelevantContextRenderer
import com.simone.jarvismobile.core.snapshot.RelevantContextSelector
import com.simone.jarvismobile.core.snapshot.SnapshotDebugInfo
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.snapshot.PersonalIntelligenceSnapshotCache
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the bounded context block `JarvisBrain` gets appended to a turn's
 * prompt (spec §7). Two lazily-included pieces, never the whole history:
 *
 *  - the caller's own [pendingTaskSnapshot] (from `ConversationManager`),
 *    included only when there actually is a pending operation — the
 *    "anzi, alle 18" case;
 *  - [MemoryEngine.retrieve] results, fetched only when
 *    [SettingsRepository.jarvisAutoContextEnabled] is on (retrieval itself is
 *    already a no-op when [SettingsRepository.jarvisMemoryEnabled] is off or
 *    the query is blank, so this is never a wasted call on a trivial turn).
 *
 * Deliberately does not reach into `KnowledgeRepository`/`DocumentImportManager`
 * (Wiki/Knowledge/documents) — that lookup stays part of Modalità Pro's role
 * in the Classic engine only, per the explicit constraint that the
 * conversational engine must not reincorporate it (see `MemoryEngine`'s doc
 * comment for the same boundary).
 *
 * The result is truncated to [SettingsRepository.jarvisContextBudgetChars] —
 * an honest proxy for a token budget, since the LLM stack surfaces no
 * tokenizer to count real tokens.
 */
/** [text] is the assembled prompt block; [memoriesRetrieved] feeds `EngineTurnDiagnostics`. */
data class AssembledContext(val text: String, val memoriesRetrieved: Int)

@Singleton
class ContextAssembler @Inject constructor(
    private val settings: SettingsRepository,
    private val memoryEngine: MemoryEngine,
    private val snapshotCache: PersonalIntelligenceSnapshotCache,
) {
    suspend fun assemble(query: String, pendingTaskSnapshot: String?): AssembledContext {
        val budget = settings.jarvisContextBudgetChars.first()
        val parts = ArrayList<String>(3)
        var memoriesRetrieved = 0

        if (!pendingTaskSnapshot.isNullOrBlank()) {
            parts += "Operazione in corso (usa il suo id se Simone la corregge senza rinominarla): $pendingTaskSnapshot"
        }

        if (settings.jarvisAutoContextEnabled.first()) {
            val memories = memoryEngine.retrieve(query)
            memoriesRetrieved = memories.size
            if (memories.isNotEmpty()) {
                parts += "Memoria rilevante:\n" + memories.joinToString("\n") { "- ${it.content}" }
            }
        }

        // Personal Intelligence Snapshot (§ fase Foundation): time/place/agenda/driving/device
        // context, built automatically — the user never types any of this. Its own toggle,
        // own runCatching: a failure here never breaks the turn (falls back to the two parts
        // above exactly as before this section existed).
        if (settings.jarvisPersonalSnapshotEnabled.first()) {
            runCatching {
                val snapshot = snapshotCache.get()
                val relevant = RelevantContextSelector.select(snapshot, AiRequestType.CHAT, query, budget = settings.snapshotBudget())
                val rendered = RelevantContextRenderer.render(relevant)
                if (rendered.isNotBlank()) parts += rendered
                if (com.simone.jarvismobile.BuildConfig.DEBUG) {
                    Log.d(TAG, SnapshotDebugInfo.from(snapshot, relevant, ageMs = 0L).describe())
                }
            }.onFailure { e -> Log.w(TAG, "personal_snapshot_context_failed ${e.javaClass.simpleName}") }
        }

        return AssembledContext(parts.joinToString("\n\n").take(budget), memoriesRetrieved)
    }

    private companion object {
        const val TAG = "ContextAssembler"
    }
}

/** Reads the five snapshot-budget settings into one [ContextBudget] — shared by both live integration points ([ContextAssembler] and [com.simone.jarvismobile.ai.AiRouter]). */
internal suspend fun SettingsRepository.snapshotBudget(): ContextBudget = ContextBudget(
    maxContextItems = snapshotMaxContextItems.first(),
    maxRecentEvents = snapshotMaxRecentEvents.first(),
    maxAgendaItems = snapshotMaxAgendaItems.first(),
    maxMemoryItems = snapshotMaxMemoryItems.first(),
    maxSerializedCharacters = snapshotMaxSerializedChars.first(),
)
