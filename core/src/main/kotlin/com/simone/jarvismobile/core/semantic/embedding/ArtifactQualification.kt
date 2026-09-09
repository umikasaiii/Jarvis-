package com.simone.jarvismobile.core.semantic.embedding

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §N. The minimum
 * explicit artifact qualification state — a synthetic/self-test head or
 * embedding cache must never be silently accepted by Android production
 * runtime as if it were real.
 */
enum class ArtifactQualification {
    /** Produced by a real, verified encoder run against a matching [SemanticEncoderContract] AND already calibrated (§ PASSAGGIO 15) — the only state ever automatically promoted to authoritative production by [LearnedHeadExport.isCompatibleWithRuntime]. */
    PRODUCTION_ELIGIBLE,

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 14 §R/§S. Produced by a
     * REAL, frozen, verified encoder (never [SYNTHETIC_SELFTEST]) with a real
     * head genuinely trained on real embeddings — but PASSAGGIO 15's
     * probability calibration / OOD threshold / abstention gates have not
     * run yet (see [LearnedHeadExport.calibrationStatus]). Deliberately NOT
     * [productionEligible]: a real-but-uncalibrated head must remain loadable
     * only for tests/diagnostics/shadow qualification, never promoted to
     * authoritative production behavior, until a future re-export flips its
     * qualification to [PRODUCTION_ELIGIBLE] after calibration succeeds.
     */
    REAL_TRAINED,

    /** Produced by the synthetic/self-test embedder (`tools/semantic_classifier/embed.py`'s `fake_embedder`) — proves the pipeline runs, carries no real semantic meaning, must never load in production. */
    SYNTHETIC_SELFTEST,

    /** No real training run has happened against a verified encoder yet — the honest current state of every artifact this project has shipped so far. */
    TRAINING_PENDING,

    /** An artifact whose contract/identity does not match what the current runtime encoder expects. */
    INCOMPATIBLE,
    ;

    /** Only [PRODUCTION_ELIGIBLE] artifacts may ever be loaded by the Android production classifier as PRIMARY/authoritative (§N, tightened by PASSAGGIO 14 §S — [REAL_TRAINED] is explicitly excluded here, calibration pending). */
    val productionEligible: Boolean get() = this == PRODUCTION_ELIGIBLE
}
