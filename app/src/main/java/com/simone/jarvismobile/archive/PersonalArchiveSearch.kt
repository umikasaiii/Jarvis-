package com.simone.jarvismobile.archive

import javax.inject.Inject
import javax.inject.Singleton

/** One personal-archive search hit, source-tagged so the reader always knows where it came from. */
data class PersonalArchiveHit(val label: String, val snippet: String, val score: Double)

/**
 * The single search implementation behind "cerca nel mio archivio" (spec §9:
 * "riutilizzabile sia dalla UI sia dal modello AI") — federates
 * [ArchiveRepository] (notes + to-watch) and [ArchiveListRepository] (shopping
 * + custom lists) into one ranked, source-labelled result list. Deliberately
 * does NOT include [com.simone.jarvismobile.knowledge.KnowledgeRepository] or
 * [com.simone.jarvismobile.document.DocumentImportManager]: those are the
 * other two layers (Knowledge, Personal Documents) with their own dedicated
 * tools/search — this class is the "Personal Archive" layer only.
 */
@Singleton
class PersonalArchiveSearch @Inject constructor(
    private val archive: ArchiveRepository,
    private val lists: ArchiveListRepository,
) {
    suspend fun search(query: String, limit: Int = 6): List<PersonalArchiveHit> {
        if (query.isBlank()) return emptyList()
        val noteHits = archive.searchSmart(query, limit = limit).map {
            PersonalArchiveHit(
                label = it.item.title,
                snippet = it.item.content.ifBlank { it.item.watchType },
                score = it.score,
            )
        }
        val listHits = lists.searchSmart(query, limit = limit).map {
            PersonalArchiveHit(
                label = "${it.item.title} (${it.listName})",
                snippet = it.item.description.ifBlank { it.item.notes },
                score = it.score,
            )
        }
        return (noteHits + listHits).sortedByDescending { it.score }.take(limit)
    }
}
