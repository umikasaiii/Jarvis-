package com.simone.jarvismobile.core.semantic.embedding

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * § FASE 2A.11 ADDENDUM §10 — the exact JSON shape
 * `tools/semantic_classifier/export.py` writes to `head_weights.json`: plain
 * weight/bias matrices for a small LINEAR (logistic regression) or one-
 * hidden-layer MLP head, never a pickle/PyTorch/TFLite blob — small enough
 * to parse with kotlinx.serialization and run as a couple of Kotlin
 * `FloatArray` matmuls (see [HeadMath]).
 *
 * Both [linear] and [mlp] normalize a binary (2-class) problem to always
 * carry >= 2 output rows/columns before export (see `export.py`'s own doc
 * comment for why sklearn's binary-classifier shape quirk would otherwise
 * silently produce wrong probabilities) — this Kotlin side never needs a
 * binary special case, only the ordinary >=2-class forward pass.
 */
@Serializable
data class LinearWeights(
    /** n_outputs rows x n_features columns. */
    val weight: List<List<Float>>,
    /** n_outputs. */
    val bias: List<Float>,
)

@Serializable
data class MlpWeights(
    /** n_features rows x n_hidden columns (sklearn's `coefs_[0]` shape). */
    val w1: List<List<Float>>,
    val b1: List<Float>,
    /** n_hidden rows x n_outputs columns (sklearn's `coefs_[-1]` shape). */
    val w2: List<List<Float>>,
    val b2: List<Float>,
    val activation: String = "relu",
)

/**
 * One trained head — used identically for intent/operation/referenceMode
 * (single-label, read via softmax+argmax) AND domain (multi-label, read via
 * one independent sigmoid per label) — the shape is the same either way;
 * only the caller's interpretation of the logits differs, exactly mirroring
 * how [SingleLabelCentroidClassifier]/[MultiLabelCentroidClassifier] share
 * the same underlying centroid-similarity primitive in
 * [PrototypeSemanticClassifierEngine].
 */
@Serializable
data class ExportedHead(
    val architecture: String,
    val labels: List<String>,
    val linear: LinearWeights? = null,
    val mlp: MlpWeights? = null,
)

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §K. [encoderContract]
 * and [artifactQualification] are the identity this pass adds — a trained
 * head produced under encoder contract X must never load against a
 * runtime using contract Y (§K's own words), and a
 * [ArtifactQualification.SYNTHETIC_SELFTEST]/[ArtifactQualification.TRAINING_PENDING]
 * head must never be accepted as production (§N). Both fields are nullable/
 * defaulted for backward compatibility with a `head_weights.json` written
 * before this pass (schema version unchanged — see [isCompatibleWithRuntime]'s
 * own doc on what an absent [encoderContract] means).
 */
@Serializable
data class LearnedHeadExport(
    val schemaVersion: Int,
    val embeddingDim: Int,
    val intent: ExportedHead,
    val domain: ExportedHead,
    val operation: ExportedHead? = null,
    val referenceMode: ExportedHead? = null,
    /** The [SemanticEncoderContract] this head's training embeddings were produced under — null for an export written before PASSAGGIO 13 (never production-eligible, see [isCompatibleWithRuntime]). */
    val encoderContract: SemanticEncoderContract? = null,
    /** Defaults to [ArtifactQualification.TRAINING_PENDING] — an export must explicitly declare [ArtifactQualification.PRODUCTION_ELIGIBLE] to ever be accepted by production runtime (§N). */
    val artifactQualification: ArtifactQualification = ArtifactQualification.TRAINING_PENDING,
) {
    /**
     * § PASSAGGIO 13 §K/§N/§Y (test items 6-9). The full production
     * acceptance gate — replaces the old dimension-only check. Rejects
     * whenever:
     * - [artifactQualification] is not [ArtifactQualification.PRODUCTION_ELIGIBLE] (§N);
     * - [encoderContract] is absent (pre-PASSAGGIO-13 export — no identity to verify, §K);
     * - [encoderContract] is not [SemanticEncoderContract.isCompatibleWith] [runtimeContract];
     * - [embeddingDim] does not match [runtimeContract]'s known dimension, when known.
     */
    fun isCompatibleWithRuntime(runtimeContract: SemanticEncoderContract): Boolean {
        if (!artifactQualification.productionEligible) return false
        val contract = encoderContract ?: return false
        if (!contract.isCompatibleWith(runtimeContract)) return false
        val runtimeDim = runtimeContract.embeddingDimension
        if (runtimeDim != null && runtimeDim != embeddingDim) return false
        return true
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }

        /** Returns null (never throws) on any malformed/unreadable export — a
         * bad `head_weights.json` must fall back to the prototype/centroid
         * classifier, never crash the app (§14: same fail-safe discipline as
         * every other asset load in this project). */
        fun parseOrNull(source: String): LearnedHeadExport? =
            runCatching { json.decodeFromString(serializer(), source) }
                .getOrNull()
                ?.takeIf { it.schemaVersion == SUPPORTED_SCHEMA_VERSION }
    }
}
