package com.simone.jarvismobile.memory

import android.util.Log
import com.simone.jarvismobile.core.memory.MarkdownNote
import com.simone.jarvismobile.core.memory.MarkdownParser
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryChunk
import com.simone.jarvismobile.core.memory.MemoryRecord
import com.simone.jarvismobile.core.memory.MemoryRecordCodec
import com.simone.jarvismobile.core.memory.HybridCandidate
import com.simone.jarvismobile.core.memory.HybridRanker
import com.simone.jarvismobile.core.memory.RankedChunk
import com.simone.jarvismobile.core.memory.RetrievalRanker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory retrieval index over the Obsidian vault (Phase 5). Notes are read via
 * [VaultRepository], parsed with the pure-Kotlin [MarkdownParser], split into
 * heading-sized chunks, and ranked at query time with the pure-Kotlin
 * [RetrievalRanker] (both live in `:core`, unit-tested). The index is a
 * rebuildable cache; the `.md` files remain the source of truth.
 *
 * This first slice keeps the whole index in memory (fine for a personal vault of
 * hundreds of notes). A Room/FTS store can back it later without touching callers.
 */
@Singleton
class MemoryIndex @Inject constructor(
    private val vault: VaultRepository,
    private val conversationMemory: ConversationMemoryStore,
    private val embeddings: EmbeddingRepository,
    private val categoryClassifier: MemoryCategoryClassifier,
) {
    /** Honest, observable state of the index for the UI. */
    data class Status(
        val configured: Boolean = false,
        val building: Boolean = false,
        val noteCount: Int = 0,
        val chunkCount: Int = 0,
        val memoryCount: Int = 0,
        val lastError: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()

    @Volatile private var chunks: List<MemoryChunk> = emptyList()
    @Volatile private var lastBuiltAt: Long = 0L
    private val ranker = RetrievalRanker()
    private val buildMutex = Mutex()

    /** Background scope for best-effort work (AI categorisation) that must never
     * block a save on the tool's short timeout. */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Builds the index only if a vault is set and it has not been built yet. */
    suspend fun ensureBuilt() {
        conversationMemory.ensureLoaded()
        if (_status.value.building) return
        if (!vault.isConfigured()) {
            _status.value = _status.value.copy(configured = false)
            return
        }
        if (lastBuiltAt == 0L || System.currentTimeMillis() - lastBuiltAt >= STALE_AFTER_MS) rebuild()
    }

    /** (Re)reads the whole vault and rebuilds the index. */
    suspend fun rebuild() = withContext(Dispatchers.Default) {
        buildMutex.withLock {
            _status.value = _status.value.copy(configured = true, building = true, lastError = null)
            try {
                val notes = vault.readAllNotes()
                val built = notes.flatMap { note ->
                    chunkNote(MarkdownParser.parse(note.path, note.content))
                }
                chunks = built
                lastBuiltAt = System.currentTimeMillis()
                _status.value = Status(
                    configured = true,
                    building = false,
                    noteCount = notes.size,
                    chunkCount = built.size,
                    memoryCount = notes.firstOrNull {
                        isExplicitMemory(it.path)
                    }?.let { MemoryRecordCodec.parse(it.content).size } ?: 0,
                )
                Log.i(TAG, "memory_indexed notes=${notes.size} chunks=${built.size}")
            } catch (t: Throwable) {
                Log.w(TAG, "memory_index_failed ${t.javaClass.simpleName}")
                _status.value = _status.value.copy(building = false, lastError = t.javaClass.simpleName)
            }
        }
    }

    /** Whether a vault is connected (so callers can offer to save memories). */
    suspend fun isConfigured(): Boolean = vault.isConfigured()

    /** Saved reminders, newest first, read straight from the vault file. */
    suspend fun listMemories(limit: Int = 10): List<String> = vault.listMemories(limit)

    suspend fun listRecords(): List<MemoryRecord> = vault.readMemoryRecords()

    /**
     * Saves a user "remember this" note into the vault and refreshes the index so
     * it is retrievable straight away. Returns false if there is no writable vault.
     */
    suspend fun remember(
        text: String,
        kind: MemoryKind? = null,
        category: String = "",
        autoCategorize: Boolean = true,
    ): MemoryRecord? {
        val resolved = kind ?: com.simone.jarvismobile.core.memory.MemoryStructure.classify(text)
        if (resolved == MemoryKind.TEMPORARY) {
            val ok = conversationMemory.addTemporary(text)
            if (!ok) return null
            val now = System.currentTimeMillis()
            return MemoryRecord("temporary-$now", text.trim(), MemoryKind.TEMPORARY, now)
        }
        // Save immediately. A category picked by hand is honoured as-is. When none
        // was given, the note stays uncategorised unless [autoCategorize] is on
        // (the default for voice/chat saves), in which case it is sorted into a
        // macro-category in the background — the on-device model classification is
        // an LLM generation that can take several seconds, and awaiting it here made
        // the save time out ("L'operazione ha impiegato troppo tempo"). A missing/
        // loading model just leaves it uncategorised. Manual "+" adds pass
        // autoCategorize=false so an unselected category means exactly "Senza
        // categoria", ready for the AI button.
        val saved = vault.addMemory(text, resolved, category) ?: return null
        rebuild()
        if (category.isBlank() && autoCategorize) {
            bgScope.launch {
                val guessed = runCatching { categoryClassifier.classify(text) }.getOrDefault("")
                if (guessed.isNotBlank() &&
                    runCatching { vault.setMemoryCategory(saved.id, guessed) }.getOrNull() != null
                ) {
                    rebuild()
                }
            }
        }
        return saved
    }

    /**
     * Re-runs the AI categorisation over records that have no category yet, so an
     * existing archive gets sorted without re-saving each note by hand. Returns
     * how many were updated.
     */
    suspend fun categorizeUncategorized(): Int {
        val pending = vault.readMemoryRecords().filter { it.category.isBlank() }
        var changed = 0
        for (record in pending) {
            val category = runCatching { categoryClassifier.classify(record.text) }.getOrDefault("")
            if (category.isNotBlank() &&
                vault.setMemoryCategory(record.id, category) != null
            ) {
                changed++
            }
        }
        if (changed > 0) rebuild()
        return changed
    }

    suspend fun update(recordId: String, text: String, kind: MemoryKind): MemoryRecord? {
        if (kind == MemoryKind.TEMPORARY) {
            if (!conversationMemory.addTemporary(text)) return null
            val removed = vault.deleteMemory(recordId) ?: return null
            rebuild()
            return removed.copy(text = text.trim(), kind = MemoryKind.TEMPORARY)
        }
        val updated = vault.updateMemory(recordId, text, kind)
        if (updated != null) rebuild()
        return updated
    }

    suspend fun delete(recordId: String): MemoryRecord? {
        val removed = vault.deleteMemory(recordId)
        if (removed != null) rebuild()
        return removed
    }

    /** Moves one record to a category chosen by hand in the memory screen. */
    suspend fun setCategory(recordId: String, category: String): MemoryRecord? {
        val updated = vault.setMemoryCategory(recordId, category)
        if (updated != null) rebuild()
        return updated
    }

    /** Records whose text contains [needle] — for voice/chat edit & delete. */
    suspend fun findMemories(needle: String): List<MemoryRecord> {
        val n = needle.trim()
        if (n.length < 2) return emptyList()
        return listRecords().filter { it.text.contains(n, ignoreCase = true) }
    }

    /**
     * Deletes the single record matching [needle]. Fails closed (null) on no
     * match or on ambiguity, so a vague "dimentica…" never removes the wrong one.
     */
    suspend fun deleteByText(needle: String): MemoryRecord? {
        val match = findMemories(needle).singleOrNull() ?: return null
        return delete(match.id)
    }

    /** Replaces the text of the single record matching [needle]; fails closed. */
    suspend fun updateByText(needle: String, newText: String): MemoryRecord? {
        val clean = newText.trim()
        if (clean.length < 2) return null
        val match = findMemories(needle).singleOrNull() ?: return null
        return update(match.id, clean, match.kind)
    }

    /** Drops the index (e.g. after the vault is disconnected). */
    fun clear() {
        chunks = emptyList()
        lastBuiltAt = 0L
        _status.value = Status(configured = false)
    }

    /**
     * Returns a bounded set of context chunks for [query]. Explicit memories are
     * indexed one entry at a time (rather than injecting the whole Memoria.md on
     * every turn); lexical matches rank normally and the two newest memories are
     * a small fallback only for explicit personal-recall questions, until
     * semantic embeddings are available.
     */
    fun retrieve(query: String, limit: Int = 4): List<RankedChunk> {
        val temporary = conversationMemory.snapshot.value.facts.mapIndexed { index, fact ->
            MemoryChunk(
                notePath = "private://conversation/$index",
                title = "Memoria breve",
                text = fact,
                tags = conversationMemory.snapshot.value.topics,
                folder = "conversation",
            )
        }
        val local = chunks + temporary
        if (local.isEmpty()) return emptyList()
        val ranked = if (query.isBlank()) emptyList() else ranker.rank(query, local, limit)
        val recentMemories = if (PERSONAL_RECALL_RE.containsMatchIn(query.lowercase())) {
            local.asReversed()
                .filter {
                    isExplicitMemory(it.notePath) || it.notePath.startsWith("private://conversation/")
                }
                .take(RECENT_MEMORY_FALLBACK)
                .map { RankedChunk(it, 0.0) }
        } else {
            emptyList()
        }
        return (ranked + recentMemories)
            .distinctBy { it.chunk.notePath to it.chunk.text }
            .take(limit)
    }

    /**
     * Like [retrieve], but blends semantic similarity with the lexical score when a
     * sentence-embedding model is imported (retrieval "by meaning"). With no model,
     * or on any embedding error, it is exactly [retrieve] — the lexical path is the
     * safety net, so this can never do worse than before.
     */
    suspend fun retrieveSmart(query: String, limit: Int = 4): List<RankedChunk> {
        if (query.isBlank()) return emptyList()
        runCatching { embeddings.ensureLoaded() }
        if (!embeddings.isReady()) return retrieve(query, limit)

        val temporary = conversationMemory.snapshot.value.facts.mapIndexed { index, fact ->
            MemoryChunk(
                notePath = "private://conversation/$index",
                title = "Memoria breve",
                text = fact,
                tags = conversationMemory.snapshot.value.topics,
                folder = "conversation",
            )
        }
        val local = chunks + temporary
        if (local.isEmpty()) return emptyList()

        val lexical = ranker.rank(query, local, local.size).associate { it.chunk to it.score }
        val semantic = embeddings.semanticScores(query, local.map { it.text })
            ?: return retrieve(query, limit)
        val candidates = local.mapIndexed { index, chunk ->
            HybridCandidate(
                ref = index.toString(),
                lexicalScore = lexical[chunk] ?: 0.0,
                semanticScore = semantic[chunk.text] ?: 0.0,
            )
        }
        val fused = HybridRanker.fuse(candidates, limit)
        val ranked = fused.mapNotNull { result ->
            local.getOrNull(result.ref.toIntOrNull() ?: -1)?.let { RankedChunk(it, result.score) }
        }
        return ranked.ifEmpty { retrieve(query, limit) }
    }

    private fun isExplicitMemory(notePath: String): Boolean =
        notePath.substringAfterLast('/').equals("Memoria.md", ignoreCase = true)

    /**
     * Splits a note into chunks by top-level headings so retrieval can surface the
     * relevant section rather than a whole long note. Each chunk carries the note's
     * title/tags/folder for scoring. Falls back to the whole body when there are no
     * headings.
     */
    private fun chunkNote(note: MarkdownNote): List<MemoryChunk> {
        val folder = note.path.substringBeforeLast('/', "")
        val sections = if (isExplicitMemory(note.path)) {
            splitMemoryEntries(note.body)
        } else {
            splitByHeadings(note.body)
        }
        if (isExplicitMemory(note.path) && sections.isEmpty()) return emptyList()
        val chunks = sections
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { section ->
                MemoryChunk(
                    notePath = note.path,
                    title = note.title,
                    text = section.take(MAX_CHUNK_CHARS),
                    tags = note.tags,
                    folder = folder,
                )
            }
        return chunks.ifEmpty {
            listOf(
                MemoryChunk(
                    notePath = note.path,
                    title = note.title,
                    text = note.body.take(MAX_CHUNK_CHARS),
                    tags = note.tags,
                    folder = folder,
                ),
            )
        }
    }

    private fun splitByHeadings(body: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (line in body.lineSequence()) {
            if (line.trimStart().startsWith("#") && current.isNotBlank()) {
                out += current.toString()
                current.setLength(0)
            }
            current.append(line).append('\n')
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }

    /** Each appended `- [timestamp] fact` is an independently retrievable memory. */
    private fun splitMemoryEntries(body: String): List<String> =
        MemoryRecordCodec.parse(body).map { it.text }

    private companion object {
        const val TAG = "JarvisMemory"
        const val MAX_CHUNK_CHARS = 1200
        const val RECENT_MEMORY_FALLBACK = 2
        const val STALE_AFTER_MS = 15_000L
        val PERSONAL_RECALL_RE = Regex(
            """\b(di me|su di me|mio|mia|miei|mie|preferit\w*|ricord\w*|sai di me|come mi chiamo)\b""",
        )
    }
}
