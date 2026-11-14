package com.simone.jarvismobile.core.knowledge.zim

import com.simone.jarvismobile.core.knowledge.KnowledgeIndexer
import com.simone.jarvismobile.core.knowledge.fts.IndexedSource
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.fts.PassageIn

/**
 * KNOWLEDGE ENGINE 2.0 — OPT-IN, selective indexing of an archive's article LEADS into the persistent [KnowledgeIndex]. Never
 * automatic and never "the whole archive": the caller sets a space budget and an article cap, the walk follows the title
 * order in batches whose cursor is committed with them (an interruption resumes from the last batch), and an archive that
 * does not fit simply ends PARTIAL — what is stored remains searchable. Whole archives stay readable directly by title
 * regardless of what was indexed.
 */
class ZimIndexer(private val zim: ZimArchive, private val index: KnowledgeIndex) {
    data class Progress(val position: Int, val total: Int, val storedPassages: Int, val bytes: Long)

    val sourceKey: String get() = "zim:${zim.uuid}"

    /**
     * Walks title positions from the stored cursor. [signature] identifies the file (e.g. its size): a different one
     * discards the old index of this archive and starts over. Returns the final state; INDEXING means "interrupted by [shouldContinue]".
     */
    fun run(
        signature: String,
        maxArticles: Int,
        budgetBytes: Long,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Progress) -> Unit = {},
    ): IndexedSource.State {
        val title = zim.metadata("Title") ?: "ZIM"
        val date = zim.metadata("Date").orEmpty()
        val needed = index.begin(sourceKey, "zim", title, title, date, signature, lexical = ZimKnowledgeSource.isLexical(zim))
        if (!needed) return index.source(sourceKey)?.state ?: IndexedSource.State.COMPLETE
        val total = minOf(zim.titleOrderCount, maxArticles)
        var pos = index.cursor(sourceKey).toIntOrNull() ?: 0
        while (pos < total) {
            if (!shouldContinue()) return IndexedSource.State.INDEXING
            val end = minOf(pos + BATCH, total)
            val batch = ArrayList<PassageIn>()
            for (i in pos until end) batch += passagesOf(i)
            val stored = index.addBatch(sourceKey, batch, end.toString(), budgetBytes)
            if (stored < batch.size) return IndexedSource.State.PARTIAL_BUDGET
            pos = end
            onProgress(Progress(pos, total, index.source(sourceKey)?.passages ?: 0, index.totalBytes()))
        }
        if (total < zim.titleOrderCount) {
            // the article cap, not the end of the archive, stopped the walk: usable, but honestly PARTIAL
            index.addBatch(sourceKey, emptyList(), pos.toString(), budgetBytes)
            markPartial()
            return IndexedSource.State.PARTIAL_BUDGET
        }
        index.finish(sourceKey)
        return IndexedSource.State.COMPLETE
    }

    private fun markPartial() = index.markPartial(sourceKey)

    private fun passagesOf(position: Int): List<PassageIn> {
        val entry = runCatching { zim.titleEntryAt(position) }.getOrNull() ?: return emptyList()
        val target = runCatching { zim.resolve(entry) }.getOrNull() ?: return emptyList()
        if (target.mimeType?.startsWith("text/html") != true) return emptyList()
        val bytes = runCatching { zim.content(target) }.getOrNull() ?: return emptyList()
        val article = ZimHtml.toText(String(bytes, Charsets.UTF_8).take(MAX_HTML_CHARS))
        if (article.text.isBlank()) return emptyList()
        val title = target.displayTitle.ifBlank { article.title.orEmpty() }
        val docKey = "zim:${zim.uuid}/${target.path}"
        return KnowledgeIndexer.chunk(docKey, title, article.text.take(MAX_TEXT_CHARS)).take(LEAD_CHUNKS)
            .mapIndexed { i, c -> PassageIn(docKey, title, c.section, c.text, lead = i == 0) }
    }

    private companion object {
        const val BATCH = 40
        const val LEAD_CHUNKS = 2
        const val MAX_HTML_CHARS = 300_000
        const val MAX_TEXT_CHARS = 8_000
    }
}
