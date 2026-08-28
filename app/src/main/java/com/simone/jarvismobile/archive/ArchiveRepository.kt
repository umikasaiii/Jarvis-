package com.simone.jarvismobile.archive

import android.util.Log
import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.archive.ArchiveStatus
import com.simone.jarvismobile.core.memory.HybridCandidate
import com.simone.jarvismobile.core.memory.HybridRanker
import com.simone.jarvismobile.core.memory.RetrievalRanker
import com.simone.jarvismobile.memory.EmbeddingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One archive item ranked for a query, with its blended score. */
data class ArchiveHit(val item: ArchiveItem, val score: Double)

/**
 * CRUD + search for the personal archive (NOTE / TO_WATCH). Ranking reuses
 * [RetrievalRanker]/[HybridRanker] verbatim — the exact algorithm
 * [com.simone.jarvismobile.memory.MemoryIndex] already uses for the vault —
 * so this is a second *store*, never a second *search engine*.
 *
 * Destructive/ambiguous by-name lookups (used by the Pro Mode tools, since a
 * voice command names an item by title, not by id) fail closed on more than
 * one match, mirroring [com.simone.jarvismobile.memory.MemoryIndex.deleteByText]:
 * a vague "cancella la nota X" must never guess which X.
 */
@Singleton
class ArchiveRepository @Inject constructor(
    private val dao: ArchiveDao,
    private val embeddings: EmbeddingRepository,
) {
    private val ranker = RetrievalRanker()

    fun observeAll(): Flow<List<ArchiveItem>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<ArchiveItem> = dao.all().map { it.toModel() }

    suspend fun byKind(kind: ArchiveKind): List<ArchiveItem> = dao.byKind(kind.name).map { it.toModel() }

    suspend fun get(id: String): ArchiveItem? = dao.byId(id)?.toModel()

    suspend fun create(
        kind: ArchiveKind,
        title: String,
        content: String = "",
        tags: List<String> = emptyList(),
        watchType: String = "",
        link: String = "",
        folder: String = "",
        pinned: Boolean = false,
    ): ArchiveItem? {
        if (title.isBlank()) return null
        val now = System.currentTimeMillis()
        val item = ArchiveItem(
            id = UUID.randomUUID().toString(),
            kind = kind,
            title = title.trim(),
            content = content.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotBlank() },
            watchType = watchType.trim(),
            status = ArchiveStatus.OPEN,
            link = link.trim(),
            folder = folder.trim(),
            pinned = pinned,
            createdAt = now,
            updatedAt = now,
        )
        runCatching { dao.upsert(item.toEntity()) }.onFailure {
            Log.w(TAG, "archive_create_failed ${it.javaClass.simpleName}")
            return null
        }
        return item
    }

    /** Partial update: null/blank-ignoring for fields not being changed. Pass explicit values to change them. */
    suspend fun update(
        id: String,
        title: String? = null,
        content: String? = null,
        tags: List<String>? = null,
        watchType: String? = null,
        status: ArchiveStatus? = null,
        link: String? = null,
        folder: String? = null,
        pinned: Boolean? = null,
    ): ArchiveItem? {
        val existing = get(id) ?: return null
        val updated = existing.copy(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: existing.title,
            content = content ?: existing.content,
            tags = tags ?: existing.tags,
            watchType = watchType ?: existing.watchType,
            status = status ?: existing.status,
            link = link ?: existing.link,
            folder = folder?.trim() ?: existing.folder,
            pinned = pinned ?: existing.pinned,
            updatedAt = System.currentTimeMillis(),
        )
        runCatching { dao.upsert(updated.toEntity()) }.onFailure {
            Log.w(TAG, "archive_update_failed ${it.javaClass.simpleName}")
            return null
        }
        return updated
    }

    suspend fun delete(id: String): Boolean {
        if (get(id) == null) return false
        return runCatching { dao.delete(id); true }.getOrDefault(false)
    }

    /** Items whose title or content contains [needle], optionally narrowed to [kind]. */
    suspend fun findByText(needle: String, kind: ArchiveKind? = null): List<ArchiveItem> {
        val n = needle.trim()
        if (n.length < 2) return emptyList()
        return all()
            .filter { kind == null || it.kind == kind }
            .filter { it.title.contains(n, ignoreCase = true) || it.content.contains(n, ignoreCase = true) }
    }

    /** Deletes the single item matching [needle]; fails closed (null) on no match or ambiguity. */
    suspend fun deleteByText(needle: String, kind: ArchiveKind? = null): ArchiveItem? {
        val match = findByText(needle, kind).singleOrNull() ?: return null
        return if (delete(match.id)) match else null
    }

    /** Marks the single item matching [needle] as [status]; fails closed like [deleteByText]. */
    suspend fun setStatusByText(needle: String, status: ArchiveStatus, kind: ArchiveKind? = null): ArchiveItem? {
        val match = findByText(needle, kind).singleOrNull() ?: return null
        return update(match.id, status = status)
    }

    /** Lexical-only search, always available. */
    suspend fun search(query: String, kind: ArchiveKind? = null, limit: Int = 5): List<ArchiveHit> {
        val candidates = all().filter { kind == null || it.kind == kind }
        if (candidates.isEmpty() || query.isBlank()) return emptyList()
        val byPath = candidates.associateBy { it.toMemoryChunk().notePath }
        return ranker.rank(query, candidates.map { it.toMemoryChunk() }, limit)
            .mapNotNull { ranked -> byPath[ranked.chunk.notePath]?.let { ArchiveHit(it, ranked.score) } }
    }

    /**
     * Lexical + semantic (when an embedding model is imported), same blend
     * [com.simone.jarvismobile.memory.MemoryIndex.retrieveSmart] uses. Falls
     * back to [search] with no embedder or on any embedding error.
     */
    suspend fun searchSmart(query: String, kind: ArchiveKind? = null, limit: Int = 5): List<ArchiveHit> {
        if (query.isBlank()) return emptyList()
        runCatching { embeddings.ensureLoaded() }
        if (!embeddings.isReady()) return search(query, kind, limit)

        val candidates = all().filter { kind == null || it.kind == kind }
        if (candidates.isEmpty()) return emptyList()
        val chunks = candidates.map { it.toMemoryChunk() }
        val byPath = candidates.associateBy { it.toMemoryChunk().notePath }

        val lexical = ranker.rank(query, chunks, chunks.size).associate { it.chunk.notePath to it.score }
        val semantic = embeddings.semanticScores(query, chunks.map { it.text })
            ?: return search(query, kind, limit)
        val hybridCandidates = chunks.map { chunk ->
            HybridCandidate(
                ref = chunk.notePath,
                lexicalScore = lexical[chunk.notePath] ?: 0.0,
                semanticScore = semantic[chunk.text] ?: 0.0,
            )
        }
        val fused = HybridRanker.fuse(hybridCandidates, limit)
        val ranked = fused.mapNotNull { r -> byPath[r.ref]?.let { ArchiveHit(it, r.score) } }
        return ranked.ifEmpty { search(query, kind, limit) }
    }

    private companion object {
        const val TAG = "JarvisArchive"
    }
}
