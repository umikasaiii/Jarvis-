package com.simone.jarvismobile.core.semantic.embedding

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §C/§G. The ONE
 * explicit semantic encoder contract: equivalent input text must produce
 * equivalent model inputs in the Android runtime and in offline training/
 * evaluation. This is that shared, versioned description — used by BOTH
 * sides (this file's JSON shape is read/written identically by
 * `tools/semantic_classifier/`'s Python scripts, see `preprocessing.py`'s
 * own doc comment).
 *
 * **Honesty, per §C's explicit instruction ("Do not assume these values...
 * If something cannot be verified: FAIL CLOSED and report it. Do not
 * silently invent a default")**: this environment has no network access to
 * fetch or inspect the real `litert-community/embeddinggemma-300m`
 * tokenizer/model artifacts (same limit documented throughout `CLAUDE.md`
 * for every other blocked integration), and no real tokenizer/model file
 * has ever been loaded here. Every field below is one of exactly two
 * kinds:
 *
 * 1. **A JARVIS preprocessing POLICY CHOICE** — ours to make regardless of
 *    the downstream model (Unicode normalization form, whitespace
 *    collapsing, padding/truncation side) — stated with full confidence,
 *    because it does not depend on inspecting the model.
 * 2. **A claim ABOUT the model/tokenizer itself** (task prefix string,
 *    exact special-token ids, tokenizer algorithm) — these are asserted
 *    only where defensible from stable public documentation of the Gemma
 *    model family (SentencePiece unigram tokenization, case-sensitive
 *    vocabulary — both long-standing, widely published facts about this
 *    model family, not fetched from a blocked network call in this
 *    session) and are marked [specialTokensVerified]/[taskPrefixVerified]
 *    = `false` wherever the EXACT value (a real BOS/EOS/PAD id, a real
 *    prefix string) has never been read from a real artifact file. A
 *    `false` flag here is a hard gate: [SemanticClassifierInterpreterAdapter][com.simone.jarvismobile.core.semantic.embedding.SemanticClassifierInterpreterAdapter]
 *    consumers must treat those fields as placeholders, never as ground
 *    truth to build a production tokenizer against (§D — this is why
 *    PASSAGGIO 13 disables the production tokenizer path rather than
 *    shipping a guessed one).
 */
enum class TokenizerFormat {
    /** SentencePiece unigram — the Gemma model family's published tokenizer algorithm (public knowledge, not independently verified against a real artifact in this environment). */
    SENTENCEPIECE_UNIGRAM,

    /** No tokenizer format has been verified/implemented for production use yet — the explicit §D fail-closed state. */
    UNVERIFIED,
}

enum class PaddingSide { RIGHT, LEFT }
enum class TruncationSide { RIGHT, LEFT }

enum class PoolingMode {
    /** Mean of per-token hidden states over only the real (non-padded) tokens, per [attentionMask]. */
    MEAN_MASKED,

    /** The model's own graph already outputs one pooled sentence-embedding vector (rank-2 output) — used as-is. */
    MODEL_NATIVE,
}

enum class EmbeddingNormalization { L2, NONE }

/**
 * § PASSAGGIO 13 §C. [contractVersion] is bumped on ANY change to a field
 * below that would change the produced token ids/mask/embedding for the
 * same input text — a version bump invalidates every embedding cache and
 * every trained head produced under the old version (§K, §O).
 */
@Serializable
data class SemanticEncoderContract(
    val contractVersion: Int,

    // --- tokenizer identity (policy: what we require; identity: what a real artifact hashes to) ---
    val tokenizerFormat: TokenizerFormat,
    /** SHA-256 of the actual tokenizer artifact file, hex-encoded — null until a real file has been loaded and hashed. Never a guessed/hardcoded value (§K). */
    val tokenizerSha256: String? = null,
    /** SHA-256 of the actual `.tflite` model file, hex-encoded — null until a real file has been loaded and hashed. */
    val modelSha256: String? = null,

    // --- preprocessing policy (JARVIS's own choice, confident) ---
    /** Unicode normalization form applied before tokenization — see [TextPreprocessor]. */
    val unicodeNormalizationForm: String = "NFC",
    /** Collapse any run of Unicode whitespace to a single ASCII space. */
    val collapseWhitespace: Boolean = true,
    /** Trim leading/trailing whitespace. */
    val trimText: Boolean = true,
    /** Casing is never altered before tokenization — Gemma-family vocabularies are case-sensitive (public knowledge — see class doc). */
    val casingPolicy: String = "PRESERVE",

    // --- model-dependent claims (only where defensible; false flags below gate their use) ---
    /** The task/instruction prefix prepended before tokenization, or null if none — see [taskPrefixVerified]. */
    val taskPrefix: String? = null,
    /** `false` until the real EmbeddingGemma variant's prefix requirement has been confirmed against real model documentation/artifact — currently always `false` (§H, this pass never claims a verified prefix). */
    val taskPrefixVerified: Boolean = false,

    val bosTokenId: Int? = null,
    val eosTokenId: Int? = null,
    val padTokenId: Int? = null,
    /** `false` until real special-token ids have been read from a real tokenizer artifact — currently always `false`. */
    val specialTokensVerified: Boolean = false,

    // --- sequence shaping (policy) ---
    /** Derived from the model artifact's own published filename convention (`embeddinggemma-300M_seq256_mixed-precision.tflite`, § FASE 2A.11) — not independently verified against real tokenizer/model metadata in this environment. */
    val maxSequenceLength: Int = 256,
    val paddingSide: PaddingSide = PaddingSide.RIGHT,
    val truncationSide: TruncationSide = TruncationSide.RIGHT,

    // --- encoder output policy ---
    val poolingMode: PoolingMode = PoolingMode.MEAN_MASKED,
    val embeddingNormalization: EmbeddingNormalization = EmbeddingNormalization.L2,
    /** Known output dimension once a real model is loaded — null before that (§FASE 2A.11 established 768 as EmbeddingGemma 300M's published full-output dimension, itself not independently verified here). */
    val embeddingDimension: Int? = null,
) {
    /**
     * § PASSAGGIO 13 §K. Two contracts are compatible for the purpose of
     * loading a trained head or reusing a cached embedding ONLY if every
     * field that could change the produced token ids/mask/embedding
     * matches exactly. Artifact identity hashes ([tokenizerSha256]/
     * [modelSha256]) are compared only when BOTH sides have a non-null
     * value — an artifact whose hash was never computed (e.g. a contract
     * describing policy only, before any real file was loaded) cannot be
     * asserted incompatible on that basis alone, but [contractVersion] and
     * every policy field below always gate compatibility regardless.
     */
    fun isCompatibleWith(other: SemanticEncoderContract): Boolean {
        if (contractVersion != other.contractVersion) return false
        if (tokenizerFormat != other.tokenizerFormat) return false
        if (unicodeNormalizationForm != other.unicodeNormalizationForm) return false
        if (collapseWhitespace != other.collapseWhitespace) return false
        if (trimText != other.trimText) return false
        if (casingPolicy != other.casingPolicy) return false
        if (taskPrefix != other.taskPrefix) return false
        if (maxSequenceLength != other.maxSequenceLength) return false
        if (paddingSide != other.paddingSide) return false
        if (truncationSide != other.truncationSide) return false
        if (poolingMode != other.poolingMode) return false
        if (embeddingNormalization != other.embeddingNormalization) return false
        if (embeddingDimension != null && other.embeddingDimension != null && embeddingDimension != other.embeddingDimension) return false
        if (tokenizerSha256 != null && other.tokenizerSha256 != null && tokenizerSha256 != other.tokenizerSha256) return false
        if (modelSha256 != null && other.modelSha256 != null && modelSha256 != other.modelSha256) return false
        return true
    }

    companion object {
        const val CURRENT_CONTRACT_VERSION = 1

        /**
         * The contract this pass declares for a hypothetical future real
         * tokenizer — [tokenizerFormat] states the REQUIRED format
         * (SentencePiece unigram); no artifact hashes, because none has
         * ever been loaded in this environment (§C fail-closed).
         */
        val CURRENT: SemanticEncoderContract = SemanticEncoderContract(
            contractVersion = CURRENT_CONTRACT_VERSION,
            tokenizerFormat = TokenizerFormat.SENTENCEPIECE_UNIGRAM,
        )

        /** The explicit "no verified tokenizer implementation exists" contract — [tokenizerFormat] itself is [TokenizerFormat.UNVERIFIED], so [isCompatibleWith] against [CURRENT] is always false by construction. */
        val UNVERIFIED: SemanticEncoderContract = SemanticEncoderContract(
            contractVersion = CURRENT_CONTRACT_VERSION,
            tokenizerFormat = TokenizerFormat.UNVERIFIED,
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun parseOrNull(source: String): SemanticEncoderContract? =
            runCatching { json.decodeFromString(serializer(), source) }.getOrNull()

        fun toJson(contract: SemanticEncoderContract): String =
            json.encodeToString(serializer(), contract)
    }
}
