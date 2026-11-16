package com.simone.jarvismobile.core.knowledge

/**
 * M2 — the retrieval behaviours added on top of the Knowledge Engine 2.x pipeline. Every one is bounded and ON by default; [LEGACY] reproduces the
 * pre-M2 behaviour exactly and exists so the benchmark can compare the two on the SAME corpus (and as a switch should a measurement ever say an
 * option does not pay for itself on a device).
 */
data class RetrievalOptions(
    /** Choose the index candidates by BM25 over a bounded scan (FTS4 `matchinfo`), instead of the first rows in docid order. */
    val bm25: Boolean = true,
    /** De-duplicate index passages against what was already read by PASSAGE identity, not by article. */
    val passageIdentityDedup: Boolean = true,
    /** When an article is longer than the chunk cap, keep the chunks that speak about the question, not the first ones. */
    val relevanceSections: Boolean = true,
    /** One bounded second pass that reads the articles the index pointed to, when the first pass found no supporting window. */
    val deepPass: Boolean = true,
    /** Rows whose `matchinfo` is read per index query (memory/time bound of the BM25 scan). */
    val scanCap: Int = 2000,
    /** Index candidates handed to the re-ranker. */
    val rerank: Int = 60,
    /** Articles read in the deep pass. */
    val maxDeepArticles: Int = 2,
    /** Total characters of converted article text kept in the per-archive read cache. */
    val articleCacheChars: Int = 2_000_000,
    /** Characters of article text considered for section selection (the HTML read is capped separately). */
    val maxArticleTextChars: Int = 300_000,
) {
    companion object {
        val LEGACY = RetrievalOptions(bm25 = false, passageIdentityDedup = false, relevanceSections = false, deepPass = false, maxArticleTextChars = 120_000)
    }
}

/** What a retrieval did, without any text of the question, of an article or of a document: only counts and names of stages. */
data class RetrievalReport(
    val sources: Int,
    val sourcesFailed: Int,
    val indexAvailable: Boolean,
    val indexFailed: Boolean,
    /** At least one indexed source stopped before everything was indexed (budget/cap/interrupted). */
    val indexPartial: Boolean,
    val deepened: Boolean,
    val articlesRead: Int,
    val issues: List<String>,
) {
    /** Some sources answered and others did not: the search was PARTIAL, not "complete with no result". */
    val partial: Boolean get() = sourcesFailed > 0 || indexFailed || indexPartial
    /** Nothing healthy could be consulted at all although something was configured. */
    val unreadable: Boolean get() = (sources > 0 && sourcesFailed == sources && !indexAvailable) || (sources == 0 && indexFailed)

    companion object { val EMPTY = RetrievalReport(0, 0, false, false, false, false, 0, emptyList()) }
}

class Gathered(val evidence: Evidence, val report: RetrievalReport)

/** `runCatching` that never swallows cancellation: a cancelled retrieval must stop, not fall back to a slower path or a partial answer. */
inline fun <T> catchingKeepCancel(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
