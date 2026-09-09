package com.simone.jarvismobile.core.semantic.embedding

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §N. The minimum
 * explicit artifact qualification state — a synthetic/self-test head or
 * embedding cache must never be silently accepted by Android production
 * runtime as if it were real.
 */
enum class ArtifactQualification {
    /** Produced by a real, verified encoder run against a matching [SemanticEncoderContract] — safe to load in production. */
    PRODUCTION_ELIGIBLE,

    /** Produced by the synthetic/self-test embedder (`tools/semantic_classifier/embed.py`'s `fake_embedder`) — proves the pipeline runs, carries no real semantic meaning, must never load in production. */
    SYNTHETIC_SELFTEST,

    /** No real training run has happened against a verified encoder yet — the honest current state of every artifact this project has shipped so far. */
    TRAINING_PENDING,

    /** An artifact whose contract/identity does not match what the current runtime encoder expects. */
    INCOMPATIBLE,
    ;

    /** Only [PRODUCTION_ELIGIBLE] artifacts may ever be loaded by the Android production classifier (§N). */
    val productionEligible: Boolean get() = this == PRODUCTION_ELIGIBLE
}
