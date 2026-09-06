package com.simone.jarvismobile.core.semantic.embedding

/**
 * § FASE 2A.11 §4 — the "prototype/centroid classifier" the spec asks for as
 * the first working implementation, before any trained classification head
 * exists (§13). For each label, the centroid is the mean of its examples'
 * (already L2-normalized) embeddings — a query is scored by cosine similarity
 * against every centroid. Two flavors, because "intent" and "domains" are
 * genuinely different classification shapes, not the same problem twice:
 *
 * - [SingleLabelCentroidClassifier]: exactly one winner (argmax), with the
 *   top-1/top-2 margin the OOD gate needs (§6) — used for intent/operation/
 *   referenceMode.
 * - [MultiLabelCentroidClassifier]: every label whose similarity clears its
 *   OWN threshold passes — used for domains, so "HEALTH e AGENDA insieme"
 *   never has to pick one (§5).
 *
 * Pure, no I/O, no model dependency — an embedding is just a [FloatArray]
 * here, real or synthetic; this class cannot tell the difference, which is
 * exactly what makes it testable without the real EmbeddingGemma model (see
 * [PrototypeSemanticClassifierEngine]'s doc comment).
 */
class SingleLabelCentroidClassifier<T>(
    /** One or more example embeddings per label — the raw material a centroid is built from. */
    examplesByLabel: Map<T, List<EmbeddingVector>>,
) {
    data class Result<T>(
        val best: T?,
        val bestScore: Float,
        val secondBest: T?,
        val secondScore: Float,
    ) {
        /**
         * `bestScore - secondScore`. With no runner-up at all (a single-label
         * corpus, [secondBest] null), [secondScore] defaults to 0, so this
         * equals [bestScore] itself — a real production corpus always has
         * multiple intents, so this only matters for a deliberately
         * single-label test fixture.
         */
        val margin: Float get() = bestScore - secondScore
    }

    private val centroids: Map<T, EmbeddingVector> =
        examplesByLabel.mapValues { (_, vectors) -> EmbeddingMath.l2Normalize(EmbeddingMath.mean(vectors.map(EmbeddingMath::l2Normalize))) }

    val labels: Set<T> get() = centroids.keys

    /** Ranks every label's centroid against [queryEmbedding] (assumed already normalized by the caller, or not — cosine similarity works either way). */
    fun classify(queryEmbedding: EmbeddingVector): Result<T> {
        if (centroids.isEmpty()) return Result(null, 0f, null, 0f)
        val scored = centroids.entries
            .map { (label, centroid) -> label to EmbeddingMath.cosineSimilarity(queryEmbedding, centroid) }
            .sortedByDescending { it.second }
        val best = scored.getOrNull(0)
        val second = scored.getOrNull(1)
        return Result(
            best = best?.first,
            bestScore = best?.second ?: 0f,
            secondBest = second?.first,
            secondScore = second?.second ?: 0f,
        )
    }
}

/** § FASE 2A.11 §5 — one similarity score per label; the caller decides which labels "pass" against per-label thresholds. */
class MultiLabelCentroidClassifier<T>(
    examplesByLabel: Map<T, List<EmbeddingVector>>,
) {
    private val centroids: Map<T, EmbeddingVector> =
        examplesByLabel.mapValues { (_, vectors) -> EmbeddingMath.l2Normalize(EmbeddingMath.mean(vectors.map(EmbeddingMath::l2Normalize))) }

    val labels: Set<T> get() = centroids.keys

    /** Every label's raw cosine similarity against [queryEmbedding] — a diagnostic-friendly full score map, never just a pass/fail bit. */
    fun scoreAll(queryEmbedding: EmbeddingVector): Map<T, Float> =
        centroids.mapValues { (_, centroid) -> EmbeddingMath.cosineSimilarity(queryEmbedding, centroid) }
}
