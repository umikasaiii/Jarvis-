package com.simone.jarvismobile.core.document

/**
 * A hybrid re-ranker over lexical candidates. Real embedding-based similarity
 * needs an on-device model that is not bundled yet; until it is, "hybrid" means
 * blending the BM25 score with cheap semantic-ish signals that a bag-of-words
 * ranker misses:
 *
 *  - **coverage**: how many *distinct* query terms the passage actually contains
 *    (a chunk that mentions every part of the question beats one that repeats a
 *    single term), and
 *  - **structure**: whether the query terms hit the passage's own heading.
 *
 * When an embedding retriever arrives it slots in as another signal here, behind
 * the unchanged [Retriever] interface — the query pipeline never changes.
 */
object HybridReranker {

    const val W_LEXICAL = 0.50
    const val W_COVERAGE = 0.40
    const val W_STRUCTURE = 0.10

    fun rerank(query: String, candidates: List<RetrievedChunk>, limit: Int): List<RetrievedChunk> {
        if (candidates.isEmpty()) return candidates
        val q = DocumentTerms.of(query).toHashSet()
        if (q.isEmpty()) return candidates.take(limit)
        val maxLexical = candidates.maxOf { it.score }.takeIf { it > 0.0 } ?: 1.0

        return candidates
            .map { rc ->
                val lexical = rc.score / maxLexical
                val bodyTerms = DocumentTerms.of(rc.chunk.text).toHashSet()
                val coverage = q.count { it in bodyTerms }.toDouble() / q.size
                val headingTerms = DocumentTerms.of(rc.chunk.section).toHashSet()
                val structure = if (headingTerms.isEmpty()) 0.0 else q.count { it in headingTerms }.toDouble() / q.size
                val blended = W_LEXICAL * lexical + W_COVERAGE * coverage + W_STRUCTURE * structure
                rc to blended
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { RetrievedChunk(it.first.chunk, it.second) }
    }
}

/**
 * The retriever the app uses: BM25 recall, then [HybridReranker] precision. It
 * pulls a wider candidate set than it returns so the re-ranker has something to
 * reorder.
 */
class HybridDocumentRetriever(
    chunks: List<DocumentChunk>,
    private val candidateFactor: Int = 3,
) : Retriever {
    private val lexical = LexicalDocumentRetriever(chunks)

    override fun retrieve(query: String, topK: Int): List<RetrievedChunk> {
        val candidates = lexical.retrieve(query, topK * candidateFactor)
        return HybridReranker.rerank(query, candidates, topK)
    }
}
