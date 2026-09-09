package com.simone.jarvismobile.llm

import com.simone.jarvismobile.core.semantic.embedding.EmbeddingVector
import kotlinx.coroutines.flow.StateFlow

/** Mirrors [LlmLoadState] for the embedding model — a separate state machine, never reusing the generative engines' own. */
enum class EmbeddingLoadState { UNLOADED, LOADING, LOADED, ERROR }

/** Set by the last [SemanticEmbeddingEngine.embed] call — read by `EmbeddingSemanticClassifier` for its own `HasSemanticTiming` contract. [coldStartMs] is non-null only on the first call after [SemanticEmbeddingEngine.load]. */
data class EmbeddingEngineTiming(val tokenizationMs: Long, val embeddingMs: Long, val coldStartMs: Long?)

/**
 * § FASE 2A.11 §1/§2 — a local, on-device SENTENCE EMBEDDING model (not
 * generative): [embed] turns text into a fixed-dimension semantic vector,
 * never text or a tool call. Deliberately a separate interface from
 * [LlmEngine] — that interface's whole contract (`generate`/`chat`/
 * `chatStateless`, a persistent native `Conversation`) describes a
 * token-by-token GENERATIVE model; an embedding model has no such thing (one
 * forward pass, one fixed-size output, no decoding loop, no conversation
 * state) — forcing it through [LlmEngine] would mean stub-implementing half
 * that interface with meaningless behavior. [EmbeddingGemmaEngine] is the
 * real implementation (EmbeddingGemma 300M, LiteRT `.tflite`, §1).
 */
interface SemanticEmbeddingEngine {
    val loadState: StateFlow<EmbeddingLoadState>
    val loadedModelName: StateFlow<String?>
    val lastLoadDetail: StateFlow<String>

    /** The fixed output dimension once loaded (768 for EmbeddingGemma 300M's full output) — null before a successful [load]. */
    val embeddingDimension: Int?

    /** § PASSAGGIO 13 §K/§O — SHA-256 of the loaded `.tflite` model file, hex-encoded. Null before a successful [load]. */
    val modelSha256: String?

    /** § PASSAGGIO 13 §K/§O — SHA-256 of the loaded tokenizer artifact file, hex-encoded. Null before a successful [load]. */
    val tokenizerSha256: String?

    /** Set by the last [embed] call. */
    val lastTiming: EmbeddingEngineTiming?

    /** Loads the `.tflite` model plus its tokenizer (§1: `embeddinggemma-300M_seq256_mixed-precision.tflite` + `sentencepiece.model`). Returns true on success. */
    suspend fun load(modelPath: String, tokenizerPath: String, modelName: String): Boolean

    fun unload()

    /**
     * Embeds [text], or null on failure (model not loaded, tokenizer error,
     * native inference error) — never a fabricated/zero vector standing in
     * for a real failure; a caller must treat null the same way
     * [com.simone.jarvismobile.core.semantic.SemanticInterpretation.Invalid]
     * is already treated (§14: `SEMANTIC_MODEL_UNAVAILABLE`, never a keyword
     * fallback).
     */
    suspend fun embed(text: String): EmbeddingVector?
}
