package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.fts.IndexedSource
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import kotlin.coroutines.cancellation.CancellationException

/**
 * KNOWLEDGE ENGINE — the ONE retrieval entry point (used by the app and by the tests): candidates from (1) the titles the
 * question names, read directly from every opened archive, and (2) the persistent full-text index, when one exists, which
 * reaches content that no title names. Both go through the SAME [EvidenceAssembler]; the index only widens the candidate pool,
 * it never decides sufficiency.
 *
 * M2: the index candidates are chosen by BM25 (see [KnowledgeIndex]); index passages are de-duplicated against what was already read by PASSAGE
 * identity (a section of an article already read is NOT dropped just because it belongs to that article); when the first pass finds no supporting
 * window, ONE bounded second pass reads the (at most [RetrievalOptions.maxDeepArticles]) articles the index pointed to and lets the same assembler
 * judge their sections; and failures are reported ([RetrievalReport]) instead of being turned into an empty result. Cancellation is never swallowed.
 */
class KnowledgeGatherer(
    private val assembler: EvidenceAssembler = EvidenceAssembler(),
    private val options: RetrievalOptions = RetrievalOptions(),
) {
    fun gather(
        question: String,
        zimSources: List<ZimKnowledgeSource>,
        index: KnowledgeIndex? = null,
        extraChunks: List<KnowledgeChunk> = emptyList(),
        limit: Int = 3,
    ): Evidence = gatherDetailed(question, zimSources, index, extraChunks, limit).evidence

    /** [shouldContinue] is polled between stages; false aborts with a [CancellationException] (the app passes its coroutine's `isActive`). */
    fun gatherDetailed(
        question: String,
        zimSources: List<ZimKnowledgeSource>,
        index: KnowledgeIndex? = null,
        extraChunks: List<KnowledgeChunk> = emptyList(),
        limit: Int = 3,
        shouldContinue: () -> Boolean = { true },
    ): Gathered {
        fun check() { if (!shouldContinue()) throw CancellationException("knowledge_cancelled") }
        val issues = ArrayList<String>()
        var failedSources = 0
        var read = 0
        zimSources.forEach { it.options = options }

        val zimHits = ArrayList<ZimKnowledgeSource.ArticleHit>()
        for (src in zimSources) {
            check()
            val r = src.articlesChecked(question, TITLE_LIMIT)
            zimHits += r.value; read += r.articlesRead
            if (r.failures.isNotEmpty()) { failedSources++; issues += r.failures.map { "source:$it" } }
        }
        check()
        var indexFailed = false
        var indexPartial = false
        index?.options = options
        val indexed: List<KnowledgeChunk> = if (index == null) emptyList() else try {
            indexPartial = index.sources().any { it.state != IndexedSource.State.COMPLETE }
            index.search(question, INDEX_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            indexFailed = true; issues += "index:" + e.javaClass.simpleName; emptyList()
        }

        val readKeys = HashSet<String>()
        val readIds = HashSet<String>()
        for (h in zimHits) for (c in h.chunks) { readIds += c.sourceId; readKeys += identity(c) }
        val fresh = if (options.passageIdentityDedup) {
            // a passage is a duplicate only if the SAME passage was already read; another section of a read article stays a candidate
            indexed.filter { c -> identity(c) !in readKeys && !(c.sourceId in readIds && zimHits.any { h -> h.chunks.any { it.sourceId == c.sourceId && contains(it.text, c.text) } }) }
        } else {
            indexed.filter { it.sourceId !in readIds }
        }
        check()
        var evidence = assembler.assemble(question, zimHits, extraChunks + fresh, limit)

        var deepened = false
        // also when the only evidence is an index-found LEAD: its terms can match the question while the answer sits in a section of that article
        val indexOnly = zimHits.isEmpty() && indexed.isNotEmpty()
        if (options.deepPass && (!supported(evidence) || indexOnly) && indexed.isNotEmpty() && zimSources.isNotEmpty()) {
            // ONE targeted second pass: the index pointed to articles whose lead shares the question's words; read their sections and let the SAME
            // assembler decide whether any of them supports the question. Bounded: a handful of articles, no archive scan, no model.
            val pointed = indexed.map { it.sourceId }.distinct().filter { it !in readIds }.take(options.maxDeepArticles)
            val deep = ArrayList<KnowledgeChunk>()
            for (key in pointed) {
                check()
                for (src in zimSources) {
                    val chunks = try { src.readDocument(key, question) } catch (e: CancellationException) { throw e } catch (e: Exception) { issues += "deep_read:" + e.javaClass.simpleName; null }
                    if (chunks != null) { deep += chunks; read++; break }
                }
            }
            if (deep.isNotEmpty()) {
                deepened = true
                val widened = assembler.assemble(question, zimHits, extraChunks + fresh + deep.filter { identity(it) !in readKeys && fresh.none { f -> identity(f) == identity(it) } }, limit)
                // a deep result is accepted only if it is supported AND names every proper noun the question names (an article about another
                // lake must not answer "il Lago Inesistente"); otherwise the first-pass evidence stands
                if (supported(widened) && namesAllEntities(question, widened)) evidence = widened
            }
        }
        val report = RetrievalReport(
            sources = zimSources.size, sourcesFailed = failedSources, indexAvailable = index != null && !indexFailed, indexFailed = indexFailed,
            indexPartial = indexPartial, deepened = deepened, articlesRead = read, issues = issues,
        )
        return Gathered(evidence, report)
    }

    /** Capitalised words of the question (not its first word) are names: each must appear (typo-tolerant) in the evidence's titles or passages. */
    private fun namesAllEntities(question: String, e: Evidence): Boolean {
        val g = e as? Evidence.Grounded ?: return true
        val names = question.split(KnowledgeSearch.NON_WORD).filter { it.isNotEmpty() }.drop(1).filter { it.first().isUpperCase() && it.length >= 3 }.map(KnowledgeText::fold)
        if (names.isEmpty()) return true
        val pool = g.passages.flatMap { sc -> KnowledgeText.words(sc.chunk.sourceTitle + " " + sc.chunk.text + " " + sc.supportWindow).map { it.folded } }.toSet()
        return names.all { n -> pool.any { w -> w == n || KnowledgeText.editDistance(n, w, if (n.length >= 7) 2 else 1) <= (if (n.length >= 7) 2 else 1) } }
    }

    private fun supported(e: Evidence) = e is Evidence.Grounded && e.supportEstablished

    private fun identity(c: KnowledgeChunk): String = c.sourceId + "|" + KnowledgeText.fold(c.section) + "|" + KnowledgeText.fold(c.text).take(160)

    private fun contains(whole: String, part: String): Boolean = part.length >= 20 && KnowledgeText.fold(whole).contains(KnowledgeText.fold(part).take(160))

    private companion object {
        const val TITLE_LIMIT = 3
        const val INDEX_LIMIT = 12
    }
}
