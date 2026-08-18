package com.simone.jarvismobile.core.archive

import com.simone.jarvismobile.core.memory.MemoryChunk

/**
 * The two genuinely new personal-archive types (spec: "Archivio"). TODO already
 * has a full Google-Tasks-style home in `core/agenda` (`AgendaEntry`, reused
 * as-is by the Pro Mode tools) and MEMORY already has one in `core/memory`
 * (Memory V2 / the vault) — this module is deliberately only for the two kinds
 * that had nowhere to live before: a free-form personal note, and a "to watch/
 * read/listen" item. DOCUMENT is `core/document`'s `DocumentRecord`, reused
 * as-is too. One coherent archive across sources, not five separate stores.
 */
enum class ArchiveKind { NOTE, TO_WATCH }

/** OPEN for an unfinished to-watch item or an ordinary note; DONE once watched/read/checked off. */
enum class ArchiveStatus { OPEN, DONE }

/**
 * One archive row. [watchType] and [status] are only meaningful for
 * [ArchiveKind.TO_WATCH] (a film/series/book/article and whether it's been
 * consumed yet); a [ArchiveKind.NOTE] leaves them at their defaults. [link] is
 * the "eventuale link/file/note" the spec asks TO_WATCH items to carry.
 */
data class ArchiveItem(
    val id: String,
    val kind: ArchiveKind,
    val title: String,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val watchType: String = "",
    val status: ArchiveStatus = ArchiveStatus.OPEN,
    val link: String = "",
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(title.isNotBlank()) { "archive item title must not be blank" }
    }

    /**
     * Shapes this row as a [MemoryChunk] so retrieval reuses [com.simone.jarvismobile.core.memory.RetrievalRanker]
     * and [com.simone.jarvismobile.core.memory.HybridRanker] verbatim — the same
     * ranking the vault already uses — instead of a second search algorithm.
     */
    fun toMemoryChunk(): MemoryChunk = MemoryChunk(
        notePath = "archive://${kind.name.lowercase()}/$id",
        title = title,
        text = listOf(content, watchType, link).filter { it.isNotBlank() }.joinToString(" — ", prefix = "$title. "),
        tags = tags,
        folder = kind.name.lowercase(),
        ageDays = (System.currentTimeMillis() - createdAt).toDouble() / DAY_MS,
    )

    private companion object {
        const val DAY_MS = 86_400_000.0
    }
}
