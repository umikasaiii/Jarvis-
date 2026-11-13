package com.simone.jarvismobile.core.knowledge.zim

import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.KnowledgeIndexer

/**
 * KNOWLEDGE ENGINE — one opened ZIM archive as a source of evidence. Candidate articles come from the title index
 * ([ZimTitleSearch]); each is read (one cluster decompressed), turned into sectioned text and chunked with the SAME
 * [KnowledgeIndexer] the document library uses, so ranking, citation and the grounding rules are shared code.
 * Nothing is stored: the archive stays the source of truth and the passages are rebuilt per question.
 */
class ZimKnowledgeSource(
    val zim: ZimArchive,
    private val titleSearch: ZimTitleSearch = ZimTitleSearch(),
) {
    val label: String = zim.metadata("Title") ?: "ZIM"
    val date: String = zim.metadata("Date").orEmpty()
    val language: String = zim.metadata("Language").orEmpty()

    data class ArticleHit(val candidate: ZimTitleSearch.Candidate, val chunks: List<KnowledgeChunk>)

    fun articles(question: String, limit: Int = 3): List<ArticleHit> =
        titleSearch.search(zim, question, limit).mapNotNull { c ->
            val bytes = runCatching { zim.content(c.entry) }.getOrNull() ?: return@mapNotNull null
            val article = ZimHtml.toText(String(bytes, Charsets.UTF_8).take(MAX_HTML_CHARS))
            val title = c.entry.displayTitle.ifBlank { article.title.orEmpty() }
            if (article.text.isBlank()) return@mapNotNull null
            val chunks = KnowledgeIndexer.chunk("zim:${zim.uuid}/${c.entry.path}", title, article.text.take(MAX_TEXT_CHARS))
                .take(MAX_CHUNKS)
                .map { it.copy(sourceKind = "zim", collection = label, date = date) }
            if (chunks.isEmpty()) null else ArticleHit(c, chunks)
        }

    private companion object {
        const val MAX_HTML_CHARS = 600_000
        const val MAX_TEXT_CHARS = 120_000
        const val MAX_CHUNKS = 80
    }
}
