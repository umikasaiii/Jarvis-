package com.simone.jarvismobile.core.knowledge.zim

import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.KnowledgeIndexer
import com.simone.jarvismobile.core.knowledge.RetrievalOptions
import com.simone.jarvismobile.core.knowledge.SupportJudge

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

    /** What one call found AND what went wrong on the way (stage names only, never text): an error is not an empty result. */
    class Checked<T>(val value: T, val failures: List<String>, val articlesRead: Int)

    var options: RetrievalOptions = RetrievalOptions()

    // ---- read cache: converted article text, bounded, per archive instance ----------------------------------------------------
    // The key is the entry path; the cache lives and dies with this ZimKnowledgeSource, i.e. with ONE opened archive (uuid + date are part of
    // [cacheIdentity]): a replaced or removed archive gets a new instance and the old text can never be served as current.
    val cacheIdentity: String get() = "${zim.uuid}|$date"
    private val cache = object : LinkedHashMap<String, Pair<String?, String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String?, String>>): Boolean = false
    }
    private var cachedChars = 0
    @Volatile var cacheHits = 0; private set

    @Volatile var cacheMisses = 0; private set
    /** Articles read and used but NOT kept because one article alone exceeds the quota. */
    @Volatile var cacheNotStored = 0; private set
    val cachedCharsNow: Int @Synchronized get() = cachedChars

    @Synchronized private fun cached(path: String, load: () -> Pair<String?, String>?): Pair<String?, String>? {
        cache[path]?.let { cacheHits++; return it }
        cacheMisses++
        val v = load() ?: return null
        // the quota is a hard limit: an article larger than the whole quota is used for this read and never kept
        if (v.second.length > options.articleCacheChars) { cacheNotStored++; return v }
        cache[path] = v; cachedChars += v.second.length
        while (cachedChars > options.articleCacheChars && cache.isNotEmpty()) {
            val eldest = cache.entries.first(); cachedChars -= eldest.value.second.length; cache.remove(eldest.key)
        }
        return v
    }

    @Synchronized fun clearCache() { cache.clear(); cachedChars = 0 }

    fun articles(question: String, limit: Int = 3): List<ArticleHit> = articlesChecked(question, limit).value

    /** Articles whose title the question names. An archive that cannot be read yields a recorded failure, never a silent empty list. */
    fun articlesChecked(question: String, limit: Int = 3): Checked<List<ArticleHit>> {
        val failures = ArrayList<String>()
        val candidates = try {
            titleSearch.search(zim, question, limit)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += "title_search:" + e.javaClass.simpleName
            emptyList()
        }
        val hits = ArrayList<ArticleHit>()
        for (c in candidates) {
            try {
                chunksOf(c.entry, question)?.let { hits += ArticleHit(c, it) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "article_read:" + e.javaClass.simpleName
            }
        }
        return Checked(hits, failures, hits.size)
    }

    /** Reads ONE article of this archive by its document key (`zim:<uuid>/<path>`), selecting the sections that speak about [question]. */
    fun readDocument(docKey: String, question: String): List<KnowledgeChunk>? {
        val prefix = "zim:${zim.uuid}/"
        if (!docKey.startsWith(prefix)) return null
        val entry = zim.findByPath('C', docKey.removePrefix(prefix)) ?: return null
        return chunksOf(zim.resolve(entry) ?: entry, question)
    }

    private fun chunksOf(entry: ZimEntry, question: String): List<KnowledgeChunk>? {
        val loaded = cached(entry.path) {
            val bytes = zim.content(entry) ?: return@cached null
            val article = ZimHtml.toText(String(bytes, Charsets.UTF_8).take(MAX_HTML_CHARS))
            if (article.text.isBlank()) null else article.title to article.text.take(options.maxArticleTextChars)
        } ?: return null
        val title = entry.displayTitle.ifBlank { loaded.first.orEmpty() }
        val all = KnowledgeIndexer.chunk("zim:${zim.uuid}/${entry.path}", title, loaded.second)
        if (all.isEmpty()) return null
        val kept = if (all.size <= MAX_CHUNKS || !options.relevanceSections) all.take(MAX_CHUNKS) else selectRelevant(all, question)
        // `lead` marks the article's first chunk; it is only the lead when the lead really is part of what is kept
        return kept.map { c -> c.copy(sourceKind = "zim", collection = label, date = date, lead = c === all.first(), lexical = lexical) }
    }

    /**
     * An article longer than the chunk cap: instead of keeping its FIRST [MAX_CHUNKS] chunks (and losing every later section), keep the lead plus the
     * chunks that share the most stems with the question (a heading that matches counts double), in document order. Position and section of each kept
     * chunk are untouched, so citations still point at the real section.
     */
    private fun selectRelevant(all: List<KnowledgeChunk>, question: String): List<KnowledgeChunk> {
        val q = SupportJudge.stems(question).toSet()
        if (q.isEmpty()) return all.take(MAX_CHUNKS)
        val scored = all.mapIndexed { i, c ->
            val body = SupportJudge.stems(c.text).toSet(); val head = SupportJudge.stems(c.section).toSet()
            val bodyRoots = body.map(SupportJudge::dimensionRoot).toSet()
            // a stem also counts through its quality-noun root (alto ↔ altezza): a CANDIDATE signal only, sufficiency is judged elsewhere
            i to (q.count { it in body || SupportJudge.dimensionRoot(it) in bodyRoots } + 2 * q.count { it in head })
        }
        val keep = HashSet<Int>().apply { add(0) }
        scored.drop(1).sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first }).take(MAX_CHUNKS - 1).forEach { keep += it.first }
        return all.filterIndexed { i, _ -> i in keep }
    }

    companion object {
        fun isLexical(zim: ZimArchive): Boolean {
            val meta = listOf("Name", "Title", "Tags", "Description", "Flavour").mapNotNull { zim.metadata(it) }.joinToString(" ").lowercase()
            return LEXICAL_MARKERS.any { it in meta }
        }

        private val LEXICAL_MARKERS = listOf("wiktionary", "wikizionario", "dizionario", "dictionary", "vocabolario", "thesaurus", "glossar")
        private const val MAX_HTML_CHARS = 600_000
        private const val MAX_CHUNKS = 80
    }
}
