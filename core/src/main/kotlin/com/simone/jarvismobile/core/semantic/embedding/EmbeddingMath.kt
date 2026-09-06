package com.simone.jarvismobile.core.semantic.embedding

import kotlin.math.sqrt

/**
 * § FASE 2A.11 — a semantic embedding is just a [FloatArray] of a fixed
 * dimension (768 for EmbeddingGemma 300M's full output, per its public model
 * card — see [com.simone.jarvismobile.core.semantic.embedding] package doc
 * for the honesty note on why this is not independently verified against the
 * real model file in this environment). Aliased for readability at call
 * sites, never a wrapper class — this stays a plain array so the hot path
 * (comparing a query against hundreds of prototype embeddings) allocates
 * nothing extra.
 */
typealias EmbeddingVector = FloatArray

/**
 * Pure vector math the centroid classifier needs — no I/O, no model
 * dependency, so it is testable with entirely synthetic vectors (this
 * project has no way to produce a REAL EmbeddingGemma embedding in a JVM
 * unit test; see [PrototypeSemanticClassifierEngine]'s own doc comment).
 */
object EmbeddingMath {

    /**
     * L2-normalizes [v] (returns a unit vector, same direction). A zero
     * vector normalizes to itself (never divides by zero) — this can only
     * happen for a genuinely degenerate embedding, never a real model output,
     * and returning it unchanged is safer than an `Infinity`/`NaN` poison.
     */
    fun l2Normalize(v: EmbeddingVector): EmbeddingVector {
        var sumSquares = 0.0
        for (x in v) sumSquares += x.toDouble() * x.toDouble()
        val norm = sqrt(sumSquares).toFloat()
        if (norm == 0f) return v
        return FloatArray(v.size) { i -> v[i] / norm }
    }

    /** Plain dot product — the cosine similarity of two ALREADY-normalized vectors. */
    fun dot(a: EmbeddingVector, b: EmbeddingVector): Float {
        require(a.size == b.size) { "embedding dimension mismatch: ${a.size} vs ${b.size}" }
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** Cosine similarity of two arbitrary (not necessarily normalized) vectors, in [-1, 1]. */
    fun cosineSimilarity(a: EmbeddingVector, b: EmbeddingVector): Float {
        require(a.size == b.size) { "embedding dimension mismatch: ${a.size} vs ${b.size}" }
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    /** The elementwise mean of one or more equal-dimension vectors — a centroid, unnormalized. */
    fun mean(vectors: List<EmbeddingVector>): EmbeddingVector {
        require(vectors.isNotEmpty()) { "cannot average zero vectors" }
        val dim = vectors.first().size
        val sum = FloatArray(dim)
        for (v in vectors) {
            require(v.size == dim) { "embedding dimension mismatch in mean(): ${v.size} vs $dim" }
            for (i in 0 until dim) sum[i] += v[i]
        }
        val n = vectors.size.toFloat()
        return FloatArray(dim) { i -> sum[i] / n }
    }
}
