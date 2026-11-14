package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource

/**
 * KNOWLEDGE ENGINE 2.0 — the ONE retrieval entry point (used by the app and by the tests): candidates from (1) the titles the
 * question names, read directly from every opened archive, and (2) the persistent full-text index, when one exists, which
 * reaches content that no title names. Both go through the SAME [EvidenceAssembler]; the index only widens the candidate pool,
 * it never decides sufficiency.
 */
class KnowledgeGatherer(private val assembler: EvidenceAssembler = EvidenceAssembler()) {
    fun gather(
        question: String,
        zimSources: List<ZimKnowledgeSource>,
        index: KnowledgeIndex? = null,
        extraChunks: List<KnowledgeChunk> = emptyList(),
        limit: Int = 3,
    ): Evidence {
        val zimHits = zimSources.flatMap { src -> runCatching { src.articles(question, TITLE_LIMIT) }.getOrDefault(emptyList()) }
        val known = zimHits.map { it.chunks.first().sourceId }.toSet()
        val indexed = index?.let { runCatching { it.search(question, INDEX_LIMIT) }.getOrDefault(emptyList()) }.orEmpty()
            .filter { it.sourceId !in known }
        return assembler.assemble(question, zimHits, extraChunks + indexed, limit)
    }

    private companion object {
        const val TITLE_LIMIT = 3
        const val INDEX_LIMIT = 12
    }
}
