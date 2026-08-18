package com.simone.jarvismobile.core.archive

import com.simone.jarvismobile.core.memory.MemoryChunk

/**
 * A named, persistent, generic list (spec §4: "non creare tabelle separate per
 * ogni tipo"). [SHOPPING] is the one auto-provisioned singleton list ("Lista
 * della spesa"); every other list — "Ricambi moto", "Regali", anything the
 * user names — is [CUSTOM]. TODO and TO_WATCH deliberately do NOT get a list
 * type here: TODO already has a complete Google-Tasks-style home in
 * `core/agenda`, and TO_WATCH already has one in [ArchiveItem] — this type
 * only covers what had nowhere generic to live (shopping + arbitrary lists).
 */
enum class ArchiveListType { SHOPPING, CUSTOM }

/** OPEN for an unchecked list entry; DONE once bought/completed/ticked off. */
enum class ListItemStatus { OPEN, DONE }

data class ArchiveList(
    val id: String,
    val name: String,
    val type: ArchiveListType,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(name.isNotBlank()) { "archive list name must not be blank" }
    }
}

/**
 * One entry of an [ArchiveList]. Generic enough for a shopping item
 * (quantity), a custom-list entry (a spare part, a gift idea), or anything
 * else a user-named list might hold — the spec explicitly asks for one
 * extensible item shape instead of a table per list kind.
 */
data class ArchiveListItem(
    val id: String,
    val listId: String,
    val title: String,
    val description: String = "",
    val status: ListItemStatus = ListItemStatus.OPEN,
    val quantity: Int? = null,
    val priority: String = "",
    val dueDate: Long? = null,
    val notes: String = "",
    val link: String = "",
    val tags: List<String> = emptyList(),
    val order: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(title.isNotBlank()) { "list item title must not be blank" }
    }

    /**
     * Shapes this item as a [MemoryChunk], carrying [listName] since the item
     * alone doesn't know it — same reuse of [com.simone.jarvismobile.core.memory.RetrievalRanker]/
     * [com.simone.jarvismobile.core.memory.HybridRanker] as [ArchiveItem.toMemoryChunk].
     */
    fun toMemoryChunk(listName: String): MemoryChunk = MemoryChunk(
        notePath = "archive://list_item/$id",
        title = title,
        text = listOf(description, notes).filter { it.isNotBlank() }
            .joinToString(" — ", prefix = "$title ($listName). "),
        tags = tags,
        folder = "list:$listName",
        ageDays = (System.currentTimeMillis() - createdAt).toDouble() / DAY_MS,
    )

    private companion object {
        const val DAY_MS = 86_400_000.0
    }
}
