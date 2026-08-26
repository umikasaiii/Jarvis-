package com.simone.jarvismobile.engine

import com.simone.jarvismobile.core.memory.MemoryEntry
import com.simone.jarvismobile.core.memory.MemoryTier
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.engine.memory.ConversationalMemoryDao
import com.simone.jarvismobile.engine.memory.toEntity
import com.simone.jarvismobile.engine.memory.toModel
import com.simone.jarvismobile.memory.ConversationMemoryStore
import com.simone.jarvismobile.memory.MemoryIndex
import kotlinx.coroutines.flow.first
import com.simone.jarvismobile.util.runCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `ConversationalJarvisEngine`'s four-tier memory, as a facade over three
 * existing stores plus one new Room table — not a fourth storage engine:
 *
 *  - [MemoryTier.WORKING]  -> the existing [ConversationMemoryStore] (bounded,
 *    conversation-scoped facts, cleared by "Nuova conversazione" — unchanged).
 *  - [MemoryTier.EPISODIC] -> the new `conversational_memory` Room table
 *    ([ConversationalMemoryDao]) — the tier that genuinely had nowhere to
 *    live before (e.g. `ConversationManager`'s pending-task snapshots).
 *  - [MemoryTier.SEMANTIC] -> the existing vault-backed [MemoryIndex]
 *    (`retrieveSmart`, already lexical+embedding fail-closed). Deliberately
 *    does NOT touch `KnowledgeRepository` (Wiki/Documents) — that stays
 *    Modalità Pro's exclusive role in the Classic engine, per the explicit
 *    constraint that Pro's Wiki/Knowledge-querying function is not
 *    reincorporated into the conversational engine.
 *  - [MemoryTier.USER] -> reserved: today's `MemoryRecord` schema has no
 *    reliable signal distinguishing "a fact about the user" from a general
 *    durable fact, so every vault-backed record surfaces as [MemoryTier.SEMANTIC]
 *    for now — documented honestly rather than guessing a distinction the
 *    data cannot support (see `docs/CONVERSATIONAL_ENGINE.md`).
 *
 * Retrieval never sends the whole history/vault to the LLM: [retrieve] always
 * returns a bounded top-N, and is a no-op (returns nothing) when
 * [SettingsRepository.jarvisMemoryEnabled] is off.
 */
@Singleton
class MemoryEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val conversationMemory: ConversationMemoryStore,
    private val episodic: ConversationalMemoryDao,
    private val semantic: MemoryIndex,
) {
    /** Bounded, relevance-ranked memories for [query]. Empty when memory is off. */
    suspend fun retrieve(query: String, topN: Int = 0): List<MemoryEntry> {
        if (!settings.jarvisMemoryEnabled.first()) return emptyList()
        val limit = if (topN > 0) topN else settings.jarvisMemoryTopN.first()
        if (limit <= 0 || query.isBlank()) return emptyList()

        val working = conversationMemory.snapshot.value.facts.mapIndexed { index, fact ->
            MemoryEntry(
                id = "working-$index",
                content = fact,
                type = "fact",
                tier = MemoryTier.WORKING,
                timestamp = conversationMemory.snapshot.value.updatedAt,
                importance = 0.6,
                source = "conversation",
                lastAccessed = conversationMemory.snapshot.value.updatedAt,
            )
        }

        val episodicHits = runCancellable { episodic.recent(limit) }.getOrDefault(emptyList())
            .map { it.toModel() }
            .filter { it.content.containsAnyWordOf(query) }

        val semanticHits = runCancellable { semantic.retrieveSmart(query, limit) }.getOrDefault(emptyList())
            .map { ranked ->
                MemoryEntry(
                    id = ranked.chunk.notePath,
                    content = ranked.chunk.text,
                    type = "vault_note",
                    tier = MemoryTier.SEMANTIC,
                    timestamp = 0L,
                    importance = ranked.score.coerceIn(0.0, 10.0) / 10.0,
                    source = "vault",
                    lastAccessed = System.currentTimeMillis(),
                )
            }

        return (working + episodicHits + semanticHits)
            .sortedByDescending { it.importance }
            .take(limit)
    }

    /** Persists one Episodic-tier entry (e.g. a `ConversationManager` snapshot). */
    suspend fun storeEpisodic(entry: MemoryEntry, sessionId: String? = null) {
        if (!settings.jarvisMemoryEnabled.first()) return
        runCancellable { episodic.upsert(entry.copy(tier = MemoryTier.EPISODIC).toEntity(sessionId)) }
    }

    suspend fun episodicById(id: String): MemoryEntry? =
        runCancellable { episodic.byId(id)?.toModel() }.getOrNull()

    suspend fun deleteEpisodic(id: String) {
        runCancellable { episodic.delete(id) }
    }

    /** Destructive: wipes the Episodic tier. Working/Semantic keep their own clear paths. */
    suspend fun clearEpisodic() {
        runCancellable { episodic.clearAll() }
    }

    private fun String.containsAnyWordOf(query: String): Boolean {
        val words = query.lowercase().split(Regex("""\s+""")).filter { it.length >= 3 }
        if (words.isEmpty()) return false
        val haystack = lowercase()
        return words.any { haystack.contains(it) }
    }
}
