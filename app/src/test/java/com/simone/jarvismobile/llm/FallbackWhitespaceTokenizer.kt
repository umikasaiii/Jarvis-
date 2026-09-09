package com.simone.jarvismobile.llm

import java.io.File

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §D. **Test-fixture-only**
 * — moved out of `app/src/main` in this passage, per the explicit
 * instruction that an approximate tokenizer must never remain a production
 * fallback. This is NOT a real SentencePiece implementation (word-split +
 * deterministic bounded hash, never EmbeddingGemma's real vocabulary ids) —
 * it exists solely so tests that need to exercise the tokenizer/embedding
 * plumbing end-to-end (without a real `.tflite`/`sentencepiece.model`
 * artifact, unavailable in this environment) have a [SemanticTokenizer] that
 * actually reports ready and encodes something deterministic. Production
 * code uses [NotReadyTokenizer] instead — see its doc comment for the
 * fail-closed rationale.
 */
class FallbackWhitespaceTokenizer : SemanticTokenizer {
    override var isReady: Boolean = false
        private set

    override fun load(tokenizerPath: String): Boolean {
        val ok = File(tokenizerPath).let { it.exists() && it.length() > 0 }
        isReady = ok
        return ok
    }

    override fun encode(text: String, maxSequenceLength: Int): IntArray? {
        if (!isReady) return null
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val ids = IntArray(maxSequenceLength) { PAD_TOKEN_ID }
        for (i in words.indices) {
            if (i >= maxSequenceLength) break
            ids[i] = 1 + (words[i].lowercase().hashCode().and(0x7fffffff) % (VOCAB_SIZE_UPPER_BOUND - 1))
        }
        return ids
    }

    private companion object {
        const val PAD_TOKEN_ID = 0
        const val VOCAB_SIZE_UPPER_BOUND = 256_000
    }
}
