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

    /**
     * A dictionary archive (Wiktionary-like), recognised from the archive's OWN metadata (Name / Title / Tags / Description), not from
     * its content. Its entries are about WORDS ("capitale: sostantivo…"): they can answer what a word means, never a question
     * about the world, so the sufficiency judge demands that every term of the question be stated together in them.
     */
    val lexical: Boolean = isLexical(zim)

    data class ArticleHit(val candidate: ZimTitleSearch.Candidate, val chunks: List<KnowledgeChunk>)

    fun articles(question: String, limit: Int = 3): List<ArticleHit> =
        titleSearch.search(zim, question, limit).mapNotNull { c ->
            val bytes = runCatching { zim.content(c.entry) }.getOrNull() ?: return@mapNotNull null
            val article = ZimHtml.toText(String(bytes, Charsets.UTF_8).take(MAX_HTML_CHARS))
            val title = c.entry.displayTitle.ifBlank { article.title.orEmpty() }
            if (article.text.isBlank()) return@mapNotNull null
            val chunks = KnowledgeIndexer.chunk("zim:${zim.uuid}/${c.entry.path}", title, article.text.take(MAX_TEXT_CHARS))
                .take(MAX_CHUNKS)
                .mapIndexed { i, c -> c.copy(sourceKind = "zim", collection = label, date = date, lead = i == 0, lexical = lexical) }
            if (chunks.isEmpty()) null else ArticleHit(c, chunks)
        }

    companion object {
        fun isLexical(zim: ZimArchive): Boolean {
            val meta = listOf("Name", "Title", "Tags", "Description", "Flavour").mapNotNull { zim.metadata(it) }.joinToString(" ").lowercase()
            return LEXICAL_MARKERS.any { it in meta }
        }

        private val LEXICAL_MARKERS = listOf("wiktionary", "wikizionario", "dizionario", "dictionary", "vocabolario", "thesaurus", "glossar")
        private const val MAX_HTML_CHARS = 600_000
        private const val MAX_TEXT_CHARS = 120_000
        private const val MAX_CHUNKS = 80
    }
}
