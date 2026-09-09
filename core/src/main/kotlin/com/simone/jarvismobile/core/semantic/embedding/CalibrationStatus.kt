package com.simone.jarvismobile.core.semantic.embedding

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14 §Q/§R. Explicit and
 * separate from [ArtifactQualification]: an artifact can be genuinely
 * [ArtifactQualification.REAL_TRAINED] (real frozen encoder, real
 * embeddings, real training) while its probability calibration / OOD
 * threshold / abstention policy — PASSAGGIO 15's own scope, never tuned
 * here — has not been established yet. Kept as its own field (not folded
 * into [ArtifactQualification]) so PASSAGGIO 15 can flip it to [CALIBRATED]
 * on the SAME artifact identity without needing to invent a new
 * qualification value, and so diagnostics can show "real, trained, not yet
 * calibrated" as a single unambiguous fact rather than inferring it.
 */
enum class CalibrationStatus {
    /** No calibration has been performed against this specific artifact yet — the honest default for every export this pass produces. */
    PENDING,

    /** PASSAGGIO 15 (or a later pass) has calibrated confidence/OOD thresholds for this exact artifact. */
    CALIBRATED,
}
