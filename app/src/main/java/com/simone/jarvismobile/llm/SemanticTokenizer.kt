package com.simone.jarvismobile.llm

/**
 * § FASE 2A.11 §1 — turns text into the token id sequence EmbeddingGemma's
 * `.tflite` graph expects, padded/truncated to a fixed sequence length (256,
 * per §1 — `embeddinggemma-300M_seq256_mixed-precision.tflite`'s own name).
 * A separate interface from [EmbeddingGemmaEngine] so a real SentencePiece
 * binding can replace [NotReadyTokenizer] without touching the
 * model-loading/inference code at all.
 */
interface SemanticTokenizer {
    val isReady: Boolean

    /** Loads the tokenizer's own data file (`sentencepiece.model`, §1). Returns true on success. */
    fun load(tokenizerPath: String): Boolean

    /** Token ids for [text], length exactly [maxSequenceLength] (padded with [padTokenId] or truncated) — null if [isReady] is false. */
    fun encode(text: String, maxSequenceLength: Int): IntArray?
}

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §D. The explicit
 * fail-closed production tokenizer: no real SentencePiece binding for
 * EmbeddingGemma has been verified in this environment (no network access
 * to fetch/inspect `litert-community/embeddinggemma-300m`'s real
 * `sentencepiece.model`, same limit documented throughout `CLAUDE.md`), so
 * this class NEVER reports ready and NEVER encodes — [load] always returns
 * `false`, [encode] always returns `null`. [EmbeddingGemmaEngine.load]
 * therefore always fails at the tokenizer step, keeping the whole semantic
 * embedding classifier explicitly `SEMANTIC_MODEL_UNAVAILABLE` (§14) rather
 * than silently running inference against approximate/hashed token ids —
 * the fail-closed option §D explicitly allows in place of shipping a guessed
 * tokenizer. This degrades safely: `EmbeddingSemanticClassifier.classify()`
 * already returns [com.simone.jarvismobile.core.semantic.embedding.SemanticClassificationResult.unavailable],
 * which the existing adapter turns into
 * [com.simone.jarvismobile.core.semantic.SemanticInterpretation.Invalid] —
 * the same safe LLM-reasoning-loop handoff already established since FASE
 * 2A.9/2A.10, never a keyword-routing resurrection.
 *
 * The former `FallbackWhitespaceTokenizer` placeholder (word-split + bounded
 * hash — never real SentencePiece ids) has been moved to
 * `app/src/test/.../llm/FallbackWhitespaceTokenizer.kt` as an explicitly
 * test-fixture-only class, per §D's instruction that such an approximation
 * must never remain a production fallback.
 */
class NotReadyTokenizer : SemanticTokenizer {
    override val isReady: Boolean = false

    override fun load(tokenizerPath: String): Boolean = false

    override fun encode(text: String, maxSequenceLength: Int): IntArray? = null
}
