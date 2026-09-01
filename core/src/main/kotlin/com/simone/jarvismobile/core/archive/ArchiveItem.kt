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

/** A note with no [ArchiveItem.folder] set falls into this bucket, same label the reference notes apps use. */
const val ARCHIVE_UNFILED_FOLDER = ""

/**
 * One archive row. [watchType] and [status] are only meaningful for
 * [ArchiveKind.TO_WATCH] (a film/series/book/article and whether it's been
 * consumed yet); a [ArchiveKind.NOTE] leaves them at their defaults. [link] is
 * the "eventuale link/file/note" the spec asks TO_WATCH items to carry.
 *
 * [folder] and [pinned] are user-organisation, deliberately separate from
 * [tags] (which feeds [toMemoryChunk]'s AI retrieval scoring, a different
 * concern): a note's single folder for browsing, and whether it sits in the
 * "In primo piano" section at the top of the list. Empty [folder]
 * ([ARCHIVE_UNFILED_FOLDER]) is "Senza categoria".
 *
 * [theme]/[spacing] are only meaningful for [ArchiveKind.NOTE] (§ richiesta
 * esplicita dell'utente: "deve essere tutto personalizzabile: sfondo dietro,
 * colore, carattere") — the exact same string vocabulary Memoria's notes
 * already use (`com.simone.jarvismobile.core.memory.MemoryNoteThemes`/
 * `MemoryLineSpacing`), not a second, incompatible one: an Archivio note
 * reuses the identical background-colour/image picker and the identical rich
 * markup syntax ([content] stores it inline, same as [com.simone.jarvismobile.core.memory.MemoryRecord.text]).
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
    val folder: String = ARCHIVE_UNFILED_FOLDER,
    val pinned: Boolean = false,
    val theme: String = "",
    val spacing: String = "",
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
