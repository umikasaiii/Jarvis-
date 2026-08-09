package com.simone.jarvismobile.core.document

import kotlin.math.ln

/** A chunk the retriever judged relevant, with its score and citation. */
data class RetrievedChunk(val chunk: DocumentChunk, val score: Double) {
    val citation: String get() = chunk.citation
}

/**
 * The retrieval contract. A lexical (BM25) implementation ships now; a hybrid
 * vector + BM25 + metadata retriever can replace it later behind this same
 * interface without touching the query pipeline.
 */
interface Retriever {
    fun retrieve(query: String, topK: Int = DocumentRetrieval.TOP_K): List<RetrievedChunk>
}

/**
 * BM25 lexical retrieval over a fixed set of chunks, with an explicit relevance
 * floor so an off-topic library returns nothing rather than a bad best-match.
 *
 * BM25 (not plain TF-IDF cosine) because document collections are longer and
 * more varied than the short personal notes the memory ranker handles: BM25's
 * length normalisation stops a long chunk from dominating on term frequency
 * alone.
 */
class LexicalDocumentRetriever(
    private val chunks: List<DocumentChunk>,
    private val minScore: Double = DocumentRetrieval.MIN_SCORE,
    private val k1: Double = 1.5,
    private val b: Double = 0.75,
) : Retriever {

    private val docs: List<List<String>> = chunks.map { terms(it.text + " " + it.section) }
    private val avgLen: Double = if (docs.isEmpty()) 0.0 else docs.sumOf { it.size }.toDouble() / docs.size
    private val df: Map<String, Int> = HashMap<String, Int>().apply {
        for (d in docs) for (t in d.toHashSet()) this[t] = (this[t] ?: 0) + 1
    }
    private val n = docs.size.toDouble()

    override fun retrieve(query: String, topK: Int): List<RetrievedChunk> {
        val q = terms(query).toHashSet()
        if (q.isEmpty() || chunks.isEmpty()) return emptyList()

        val scored = chunks.mapIndexed { i, chunk ->
            val doc = docs[i]
            if (doc.isEmpty()) return@mapIndexed RetrievedChunk(chunk, 0.0)
            val tf = doc.groupingBy { it }.eachCount()
            val len = doc.size.toDouble()
            var score = 0.0
            for (term in q) {
                val f = tf[term] ?: continue
                val idf = ln(1.0 + (n - (df[term] ?: 0) + 0.5) / ((df[term] ?: 0) + 0.5))
                val denom = f + k1 * (1 - b + b * len / avgLen.coerceAtLeast(1.0))
                score += idf * (f * (k1 + 1)) / denom
            }
            // A chunk whose heading itself matches the query is worth more.
            val headingHits = terms(chunk.section).toHashSet().count { it in q }
            if (headingHits > 0) score *= 1.0 + 0.15 * headingHits
            RetrievedChunk(chunk, score)
        }

        return scored.filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK.coerceAtLeast(1))
    }

    private fun terms(text: String): List<String> = DocumentTerms.of(text)
}

/** Shared tokenizer/stemmer for document search, used by every retriever. */
internal object DocumentTerms {
    fun of(text: String): List<String> = text.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        .split(NON_WORD)
        .filter { it.length >= 3 && it !in STOPWORDS }

    private val NON_WORD = Regex("""[^\p{L}\p{Nd}]+""")
    private val STOPWORDS = setOf(
        "che", "chi", "con", "come", "cosa", "del", "della", "delle", "degli", "dei",
        "per", "una", "uno", "nel", "nella", "nelle", "negli", "sono", "quando",
        "dove", "questo", "questa", "quello", "quella", "the", "and", "for", "with",
        "dice", "dico", "documento", "file", "pdf", "manuale", "jarvis",
    )
}

/**
 * The two-stage budget: retrieve up to [TOP_K] candidates, then keep only the
 * best [MAX_CONTEXT] that fit the token budget. The whole vault is never put in
 * the prompt.
 */
object DocumentRetrieval {
    const val TOP_K = 15
    const val MAX_CONTEXT = 6
    const val MIN_SCORE = 0.20
    const val DEFAULT_TOKEN_BUDGET = 1_800

    /**
     * Ranks with [retriever] and trims to [maxContext] chunks whose combined
     * [DocumentChunk.tokenEstimate] stays within [tokenBudget].
     */
    fun select(
        query: String,
        retriever: Retriever,
        topK: Int = TOP_K,
        maxContext: Int = MAX_CONTEXT,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    ): List<RetrievedChunk> {
        val candidates = retriever.retrieve(query, topK)
        val kept = ArrayList<RetrievedChunk>()
        var tokens = 0
        for (c in candidates) {
            if (kept.size >= maxContext) break
            val cost = c.chunk.tokenEstimate
            if (kept.isNotEmpty() && tokens + cost > tokenBudget) continue
            kept += c
            tokens += cost
        }
        return kept
    }

    /**
     * Builds the grounded context block handed to the model: each passage tagged
     * with a bracketed source so the model can cite it and the answer stays
     * traceable to a file.
     */
    fun groundedContext(passages: List<RetrievedChunk>): String =
        passages.joinToString("\n\n") { "[Fonte: ${it.citation}]\n${it.chunk.text}" }
}
