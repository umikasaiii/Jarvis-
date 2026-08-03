package com.simone.jarvismobile.core.memory

import kotlin.math.ln

/** A candidate memory chunk to be ranked for retrieval. */
data class MemoryChunk(
    val notePath: String,
    val title: String,
    val text: String,
    val tags: List<String> = emptyList(),
    val folder: String = "",
    /** Age of the note in days; lower is more recent. */
    val ageDays: Double = 0.0,
)

data class RankedChunk(val chunk: MemoryChunk, val score: Double)

/**
 * Pre-embedding retrieval ranker (docs/ARCHITECTURE.md §14). Until a local
 * embedding model is wired in, retrieval is a transparent, explainable blend of:
 *   full-text term overlap + title match + tag match + recency + folder + keywords.
 *
 * Deterministic and pure so it can be unit-tested and reasoned about. Scores are
 * not probabilities — only their ordering matters.
 */
class RetrievalRanker(
    private val titleWeight: Double = 3.0,
    private val tagWeight: Double = 2.0,
    private val bodyWeight: Double = 1.0,
    private val recencyWeight: Double = 1.5,
    private val folderBonus: Double = 0.5,
) {
    fun rank(query: String, chunks: List<MemoryChunk>, limit: Int = 8): List<RankedChunk> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        return chunks
            .map { RankedChunk(it, score(terms, it)) }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<RankedChunk> { it.score }.thenBy { it.chunk.notePath })
            .take(limit)
    }

    private fun score(terms: Set<String>, chunk: MemoryChunk): Double {
        val titleTokens = tokenize(chunk.title)
        val bodyTokens = tokenizeList(chunk.text)
        val tagTokens = chunk.tags.map { it.lowercase() }.toSet()

        val titleMatch = terms.count { it in titleTokens } * titleWeight
        val tagMatch = terms.count { it in tagTokens } * tagWeight

        // Body: term-frequency with diminishing returns (log) so a spammy repeat
        // does not dominate a genuine multi-term match.
        var bodyMatch = 0.0
        for (t in terms) {
            val tf = bodyTokens.count { it == t }
            if (tf > 0) bodyMatch += (1.0 + ln(tf.toDouble())) * bodyWeight
        }

        val recency = recencyWeight / (1.0 + chunk.ageDays / 30.0)
        val folder = if (terms.any { chunk.folder.lowercase().contains(it) }) folderBonus else 0.0

        val overlap = titleMatch + tagMatch + bodyMatch
        // Recency only rewards chunks that already have some lexical relevance.
        return if (overlap <= 0.0) 0.0 else overlap + recency + folder
    }

    private fun tokenize(s: String): Set<String> = tokenizeList(s).toSet()

    private fun tokenizeList(s: String): List<String> =
        s.lowercase()
            .split(NON_WORD)
            .filter { it.length >= 2 && it !in STOPWORDS }

    companion object {
        private val NON_WORD = Regex("""[^\p{L}\p{Nd}]+""")
        // Small Italian stopword list — enough to stop function words from
        // dominating overlap without needing a full NLP dependency.
        private val STOPWORDS = setOf(
            "il", "lo", "la", "le", "gli", "un", "uno", "una", "di", "da", "del",
            "della", "dei", "delle", "che", "chi", "cui", "con", "per", "tra",
            "fra", "in", "su", "come", "quando", "dove", "cosa", "mi", "ti", "si",
            "ci", "vi", "ne", "è", "sono", "hai", "ho", "ha", "al", "allo", "alla",
            "ai", "agli", "alle", "non", "più", "e", "o", "ma", "se", "sì",
        )
    }
}
