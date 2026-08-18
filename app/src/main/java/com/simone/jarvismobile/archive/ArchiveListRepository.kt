package com.simone.jarvismobile.archive

import android.util.Log
import com.simone.jarvismobile.core.archive.ArchiveList
import com.simone.jarvismobile.core.archive.ArchiveListItem
import com.simone.jarvismobile.core.archive.ArchiveListType
import com.simone.jarvismobile.core.archive.ListItemStatus
import com.simone.jarvismobile.core.memory.HybridCandidate
import com.simone.jarvismobile.core.memory.HybridRanker
import com.simone.jarvismobile.core.memory.RetrievalRanker
import com.simone.jarvismobile.memory.EmbeddingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One list item ranked for a query, with its parent list name for citation. */
data class ArchiveListItemHit(val item: ArchiveListItem, val listName: String, val score: Double)

/**
 * CRUD for generic archive lists (spec §4): the one auto-provisioned "Lista
 * della spesa" ([ArchiveListType.SHOPPING]) plus any number of user-named
 * [ArchiveListType.CUSTOM] lists ("Ricambi moto"). By-name lookups fail
 * closed on ambiguity, same rule as [ArchiveRepository] and every other
 * by-name lookup in this app — a vague "aggiungi X alla lista" must never
 * guess which list.
 */
@Singleton
class ArchiveListRepository @Inject constructor(
    private val dao: ArchiveListDao,
    private val embeddings: EmbeddingRepository,
) {
    private val ranker = RetrievalRanker()
    private val shoppingMutex = Mutex()

    fun observeLists(): Flow<List<ArchiveList>> = dao.observeLists().map { rows -> rows.map { it.toModel() } }

    suspend fun lists(): List<ArchiveList> = dao.allLists().map { it.toModel() }

    fun observeAllItems(): Flow<List<ArchiveListItem>> = dao.observeAllItems().map { rows -> rows.map { it.toModel() } }

    /** The singleton shopping list, created on first use. Never a second one. */
    suspend fun shoppingList(): ArchiveList {
        shoppingMutex.withLock {
            dao.listByType(ArchiveListType.SHOPPING.name)?.let { return it.toModel() }
            val now = System.currentTimeMillis()
            val list = ArchiveList(UUID.randomUUID().toString(), "Lista della spesa", ArchiveListType.SHOPPING, now, now)
            dao.upsertList(list.toEntity())
            return list
        }
    }

    /** Creates a new named [ArchiveListType.CUSTOM] list, or returns the existing one if the name already matches. */
    suspend fun createList(name: String): ArchiveList? {
        val n = name.trim()
        if (n.length < 2) return null
        findListByName(n)?.let { return it }
        val now = System.currentTimeMillis()
        val list = ArchiveList(UUID.randomUUID().toString(), n, ArchiveListType.CUSTOM, now, now)
        runCatching { dao.upsertList(list.toEntity()) }.onFailure {
            Log.w(TAG, "archive_list_create_failed ${it.javaClass.simpleName}")
            return null
        }
        return list
    }

    suspend fun renameList(needle: String, newName: String): ArchiveList? {
        val n = newName.trim()
        if (n.length < 2) return null
        val existing = findListByName(needle) ?: return null
        val updated = existing.copy(name = n, updatedAt = System.currentTimeMillis())
        dao.upsertList(updated.toEntity())
        return updated
    }

    suspend fun deleteList(needle: String): Boolean {
        val list = findListByName(needle) ?: return false
        if (list.type == ArchiveListType.SHOPPING) return false // the shopping list is never deleted, only emptied
        dao.deleteItemsOfList(list.id)
        dao.deleteList(list.id)
        return true
    }

    /**
     * Resolves a list by (a fragment of) its name. "spesa"/"acquisti"/"comprare"
     * always resolve to the shopping list regardless of its exact title. Fails
     * closed on more than one custom-list match.
     */
    suspend fun findListByName(needle: String): ArchiveList? {
        val n = needle.trim()
        if (n.isBlank()) return null
        if (SHOPPING_ALIASES.any { n.contains(it, ignoreCase = true) }) return shoppingList()
        val all = lists()
        return all.filter { it.name.contains(n, ignoreCase = true) }.singleOrNull()
    }

    suspend fun itemsOf(listNeedle: String): Pair<ArchiveList, List<ArchiveListItem>>? {
        val list = findListByName(listNeedle) ?: return null
        return list to dao.itemsForList(list.id).map { it.toModel() }
    }

    /** Adds an item to the named list, auto-creating a CUSTOM list on first use if it doesn't exist yet. */
    suspend fun addItem(
        listNeedle: String,
        title: String,
        quantity: Int? = null,
        priority: String = "",
        notes: String = "",
        link: String = "",
        tags: List<String> = emptyList(),
    ): Pair<ArchiveList, ArchiveListItem>? {
        val t = title.trim()
        if (t.length < 2) return null
        val list = findListByName(listNeedle) ?: createList(listNeedle) ?: return null
        val now = System.currentTimeMillis()
        val item = ArchiveListItem(
            id = UUID.randomUUID().toString(),
            listId = list.id,
            title = t,
            quantity = quantity,
            priority = priority.trim(),
            notes = notes.trim(),
            link = link.trim(),
            tags = tags,
            createdAt = now,
            updatedAt = now,
        )
        runCatching { dao.upsertItem(item.toEntity()) }.onFailure {
            Log.w(TAG, "archive_list_item_create_failed ${it.javaClass.simpleName}")
            return null
        }
        return list to item
    }

    /** Partial update of an item found by (a fragment of) its title within [listNeedle]. Fails closed on ambiguity. */
    suspend fun updateItem(
        listNeedle: String,
        itemNeedle: String,
        title: String? = null,
        description: String? = null,
        status: ListItemStatus? = null,
        quantity: Int? = null,
        priority: String? = null,
        notes: String? = null,
        link: String? = null,
    ): ArchiveListItem? {
        val list = findListByName(listNeedle) ?: return null
        val match = dao.itemsForList(list.id).map { it.toModel() }
            .filter { it.title.contains(itemNeedle, ignoreCase = true) }
            .singleOrNull() ?: return null
        val updated = match.copy(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: match.title,
            description = description ?: match.description,
            status = status ?: match.status,
            quantity = quantity ?: match.quantity,
            priority = priority ?: match.priority,
            notes = notes ?: match.notes,
            link = link ?: match.link,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertItem(updated.toEntity())
        return updated
    }

    suspend fun completeItem(listNeedle: String, itemNeedle: String, done: Boolean): ArchiveListItem? =
        updateItem(listNeedle, itemNeedle, status = if (done) ListItemStatus.DONE else ListItemStatus.OPEN)

    suspend fun removeItem(listNeedle: String, itemNeedle: String): ArchiveListItem? {
        val list = findListByName(listNeedle) ?: return null
        val match = dao.itemsForList(list.id).map { it.toModel() }
            .filter { it.title.contains(itemNeedle, ignoreCase = true) }
            .singleOrNull() ?: return null
        dao.deleteItem(match.id)
        return match
    }

    /** Lexical-only search across every list's items, always available. */
    suspend fun search(query: String, limit: Int = 5): List<ArchiveListItemHit> {
        if (query.isBlank()) return emptyList()
        val allLists = lists().associateBy { it.id }
        val allItems = dao.allItems().map { it.toModel() }
        if (allItems.isEmpty()) return emptyList()
        val nameFor = { listId: String -> allLists[listId]?.name ?: "" }
        val byPath = allItems.associateBy { "archive://list_item/${it.id}" }
        return ranker.rank(query, allItems.map { it.toMemoryChunk(nameFor(it.listId)) }, limit)
            .mapNotNull { ranked -> byPath[ranked.chunk.notePath]?.let { ArchiveListItemHit(it, nameFor(it.listId), ranked.score) } }
    }

    /** Lexical + semantic blend, mirroring [ArchiveRepository.searchSmart]. */
    suspend fun searchSmart(query: String, limit: Int = 5): List<ArchiveListItemHit> {
        if (query.isBlank()) return emptyList()
        runCatching { embeddings.ensureLoaded() }
        if (!embeddings.isReady()) return search(query, limit)

        val allLists = lists().associateBy { it.id }
        val nameFor = { listId: String -> allLists[listId]?.name ?: "" }
        val allItems = dao.allItems().map { it.toModel() }
        if (allItems.isEmpty()) return emptyList()
        val chunks = allItems.map { it.toMemoryChunk(nameFor(it.listId)) }
        val byPath = allItems.associateBy { "archive://list_item/${it.id}" }

        val lexical = ranker.rank(query, chunks, chunks.size).associate { it.chunk.notePath to it.score }
        val semantic = embeddings.semanticScores(query, chunks.map { it.text }) ?: return search(query, limit)
        val hybridCandidates = chunks.map { chunk ->
            HybridCandidate(
                ref = chunk.notePath,
                lexicalScore = lexical[chunk.notePath] ?: 0.0,
                semanticScore = semantic[chunk.text] ?: 0.0,
            )
        }
        val fused = HybridRanker.fuse(hybridCandidates, limit)
        val ranked = fused.mapNotNull { r -> byPath[r.ref]?.let { ArchiveListItemHit(it, nameFor(it.listId), r.score) } }
        return ranked.ifEmpty { search(query, limit) }
    }

    private companion object {
        const val TAG = "JarvisArchiveList"
        val SHOPPING_ALIASES = listOf("spesa", "acquist", "comprare", "shopping")
    }
}
