package com.simone.jarvismobile.memory

import android.util.Log
import com.simone.jarvismobile.core.memory.MarkdownNote
import com.simone.jarvismobile.core.memory.MarkdownParser
import com.simone.jarvismobile.core.memory.MemoryChunk
import com.simone.jarvismobile.core.memory.RankedChunk
import com.simone.jarvismobile.core.memory.RetrievalRanker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    /** Honest, observable state of the index for the UI. */
    data class Status(
        val configured: Boolean = false,
        val building: Boolean = false,
        val noteCount: Int = 0,
        val chunkCount: Int = 0,
        val lastError: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()

    @Volatile private var chunks: List<MemoryChunk> = emptyList()
    private val ranker = RetrievalRanker()
    private val buildMutex = Mutex()

    /** Builds the index only if a vault is set and it has not been built yet. */
    suspend fun ensureBuilt() {
        if (chunks.isNotEmpty() || _status.value.building) return
        if (!vault.isConfigured()) {
            _status.value = _status.value.copy(configured = false)
            return
        }
        rebuild()
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
                _status.value = Status(
                    configured = true,
                    building = false,
                    noteCount = notes.size,
                    chunkCount = built.size,
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

    /**
     * Saves a user "remember this" note into the vault and refreshes the index so
     * it is retrievable straight away. Returns false if there is no writable vault.
     */
    suspend fun remember(text: String): Boolean {
        val ok = vault.appendMemory(text)
        if (ok) rebuild()
        return ok
    }

    /** Drops the index (e.g. after the vault is disconnected). */
    fun clear() {
        chunks = emptyList()
        _status.value = Status(configured = false)
    }

    /** Returns the top matching chunks for [query], or empty if nothing relevant. */
    fun retrieve(query: String, limit: Int = 4): List<RankedChunk> {
        val local = chunks
        if (local.isEmpty() || query.isBlank()) return emptyList()
        return ranker.rank(query, local, limit)
    }

    /**
     * Splits a note into chunks by top-level headings so retrieval can surface the
     * relevant section rather than a whole long note. Each chunk carries the note's
     * title/tags/folder for scoring. Falls back to the whole body when there are no
     * headings.
     */
    private fun chunkNote(note: MarkdownNote): List<MemoryChunk> {
        val folder = note.path.substringBeforeLast('/', "")
        val sections = splitByHeadings(note.body)
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

    private companion object {
        const val TAG = "JarvisMemory"
        const val MAX_CHUNK_CHARS = 1200
    }
}
