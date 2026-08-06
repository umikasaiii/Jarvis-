package com.simone.jarvismobile.core.knowledge

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Lexical search over the local library, with an explicit "I have nothing good
 * enough" outcome.
 *
 * The threshold is the point of this class. A ranker always returns a best
 * match, and a best match out of bad matches is exactly how an assistant ends up
 * confidently answering with an irrelevant paragraph. Below [MIN_SCORE] the
 * answer is [Evidence.NoEvidence], and JARVIS says it does not know — which is
 * the behaviour asked for in docs/LOCAL_KNOWLEDGE.md.
 */
class KnowledgeSearch(private val minScore: Double = MIN_SCORE) {

    /**
     * Ranks [chunks] against [question] with a TF-IDF cosine over word stems,
     * plus a small bonus when the section heading matches the question.
     */
    fun search(question: String, chunks: List<KnowledgeChunk>, limit: Int = 4): Evidence {
        val queryTerms = terms(question)
        if (queryTerms.isEmpty() || chunks.isEmpty()) return Evidence.NoEvidence

        val docs = chunks.map { terms(it.text + " " + it.section) }
        val df = HashMap<String, Int>()
        for (d in docs) for (t in d.toSet()) df[t] = (df[t] ?: 0) + 1
        val n = docs.size.toDouble()

        fun idf(t: String): Double = ln(1.0 + n / (1.0 + (df[t] ?: 0)))

        val qWeights = queryTerms.groupingBy { it }.eachCount()
            .mapValues { (t, c) -> (1 + ln(c.toDouble())) * idf(t) }
        val qNorm = sqrt(qWeights.values.sumOf { it * it })
        if (qNorm == 0.0) return Evidence.NoEvidence

        val scored = chunks.mapIndexed { i, chunk ->
            val counts = docs[i].groupingBy { it }.eachCount()
            if (counts.isEmpty()) return@mapIndexed ScoredChunk(chunk, 0.0)
            val dWeights = counts.mapValues { (t, c) -> (1 + ln(c.toDouble())) * idf(t) }
            val dNorm = sqrt(dWeights.values.sumOf { it * it })
            if (dNorm == 0.0) return@mapIndexed ScoredChunk(chunk, 0.0)

            var dot = 0.0
            for ((t, qw) in qWeights) dWeights[t]?.let { dot += qw * it }
            var score = dot / (qNorm * dNorm)

            // A passage whose own heading answers the question is worth more
            // than one that merely mentions the words in passing.
            val headingTerms = terms(chunk.section).toSet()
            if (headingTerms.isNotEmpty()) {
                val overlap = queryTerms.toSet().count { it in headingTerms }
                if (overlap > 0) score *= 1.0 + 0.25 * overlap
            }
            ScoredChunk(chunk, score)
        }

        val best = scored.filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)

        return if (best.isEmpty()) Evidence.NoEvidence else Evidence.Grounded(best)
    }

    private fun terms(text: String): List<String> = text.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        .split(NON_WORD)
        .asSequence()
        .filter { it.length >= 3 && it !in STOPWORDS }
        .map(::stem)
        .toList()

    /**
     * Crude Italian suffix stripping so "gomme"/"gomma" and "cambiare"/"cambio"
     * meet. A real stemmer is overkill for a personal library and would need a
     * dependency; this is enough to stop plural/singular from hiding a passage.
     */
    private fun stem(w: String): String {
        if (w.length <= 4) return w
        for (s in SUFFIXES) {
            if (w.length - s.length >= 3 && w.endsWith(s)) return w.dropLast(s.length)
        }
        return w
    }

    private companion object {
        /**
         * Cosine similarity below this is treated as no answer. Tuned so a
         * question about a topic absent from the library scores under it even
         * when it shares common words with some passage.
         */
        const val MIN_SCORE = 0.12

        val NON_WORD = Regex("""[^\p{L}\p{Nd}]+""")

        val SUFFIXES = listOf(
            "amento", "azione", "zione", "mente", "ando", "endo", "arsi", "ersi", "irsi",
            "are", "ere", "ire", "ato", "ata", "ati", "ate", "ito", "ita", "iti", "ite",
            "oni", "one", "che", "chi", "gli", "he", "hi", "i", "e", "o", "a",
        )

        val STOPWORDS = setOf(
            "che", "chi", "con", "come", "cosa", "del", "della", "delle", "degli", "dei",
            "per", "una", "uno", "nel", "nella", "nelle", "negli", "sono", "quando",
            "dove", "questo", "questa", "quello", "quella", "mio", "mia", "miei", "mie",
            "devo", "voglio", "fare", "anche", "solo", "non", "piu", "gli", "sul", "sulla",
            "come", "quale", "quali", "posso", "puoi", "dirmi", "spiegami", "jarvis",
        )
    }
}
