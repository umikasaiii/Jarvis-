package com.simone.jarvismobile.core.semantic.embedding

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §S. The internal
 * failure taxonomy the encoder/classifier pipeline must distinguish —
 * never exposed to normal user conversation (that stays the existing
 * `SEMANTIC_MODEL_UNAVAILABLE` handoff, § FASE 2A.9/2A.10 — see
 * `EmbeddingSemanticClassifier`/`SemanticClassifierInterpreterAdapter`,
 * untouched by this pass). This enum is for DIAGNOSTICS and internal
 * gating only.
 */
enum class SemanticEncoderFailure {
    /** No tokenizer artifact is configured/present at all. */
    TOKENIZER_MISSING,

    /** A tokenizer artifact is present but its format/identity could not be verified against the contract this build expects — the explicit PASSAGGIO 13 §D fail-closed path (no real SentencePiece implementation exists yet). */
    TOKENIZER_INCOMPATIBLE,

    /** No model artifact is configured/present at all. */
    MODEL_MISSING,

    /** The loaded model's real input/output tensor shapes, dtypes, or rank do not match [SemanticEncoderContract]'s expectations (§I). */
    MODEL_CONTRACT_INCOMPATIBLE,

    /** The model and tokenizer artifacts loaded do not share the identity a trained head/embedding cache was produced against (§K). */
    ARTIFACT_MISMATCH,

    /** Tokenization itself failed for this specific input (never a silent empty/garbage result). */
    ENCODING_FAILURE,

    /** The native inference call failed (native exception, malformed output, NaN/zero-vector result — §J). */
    INFERENCE_FAILURE,

    /** A learned head asset was found but its contract/artifact identity does not match the currently loaded encoder (§K, §7 of §Y). */
    HEAD_INCOMPATIBLE,
}
