package com.simone.jarvismobile.core.memory

import kotlin.math.sqrt

/**
 * Turns text into an embedding vector, on-device and offline. The real
 * implementation (ONNX, in `app/`) is swappable behind this interface, exactly
 * like the LLM and neural-TTS engines. When no embedding model is imported there
 * is no embedder and retrieval falls back to the lexical [RetrievalRanker].
 */
interface Embedder {
    /** Length of the vectors this embedder produces. */
    val dimension: Int

    /** The embedding of [text]. Same length as [dimension]; deterministic. */
    fun embed(text: String): FloatArray
}

/** Small, dependency-free vector helpers for cosine-similarity retrieval. */
object VectorMath {
    /**
     * Cosine similarity in [-1, 1]; 1 = same direction (same meaning), 0 =
     * unrelated. Returns 0 for empty or mismatched vectors rather than throwing,
     * so a bad embedding degrades gracefully instead of crashing recall.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom <= 0.0) 0f else (dot / denom).toFloat()
    }
}

/** One candidate to be fused: its lexical score and its semantic similarity. */
data class HybridCandidate(
    val ref: String,
    val lexicalScore: Double,
    val semanticScore: Double,
)

data class HybridResult(val ref: String, val score: Double)

/**
 * Fuses lexical retrieval (words/stems/synonyms) with semantic similarity
 * (meaning) into one ranking. Semantic leads because it catches paraphrases the
 * lexical ranker misses; lexical stays in the blend as a safety net for exact
 * names, numbers and rare terms an embedder can blur.
 *
 * The two scores live on different scales, so each is normalised before the
 * weighted sum: lexical by the batch maximum, semantic by mapping cosine [-1,1]
 * to [0,1]. Pure and deterministic, so the fusion is unit-tested.
 */
object HybridRanker {
    const val W_SEMANTIC = 0.6
    const val W_LEXICAL = 0.4

    fun fuse(
        candidates: List<HybridCandidate>,
        limit: Int = 8,
        wSemantic: Double = W_SEMANTIC,
        wLexical: Double = W_LEXICAL,
    ): List<HybridResult> {
        if (candidates.isEmpty()) return emptyList()
        val maxLex = candidates.maxOf { it.lexicalScore }
        return candidates
            .map { c ->
                val normLex = if (maxLex > 0.0) c.lexicalScore / maxLex else 0.0
                val normSem = (c.semanticScore.coerceIn(-1.0, 1.0) + 1.0) / 2.0
                HybridResult(c.ref, wLexical * normLex + wSemantic * normSem)
            }
            .sortedWith(compareByDescending<HybridResult> { it.score }.thenBy { it.ref })
            .take(limit)
    }
}
