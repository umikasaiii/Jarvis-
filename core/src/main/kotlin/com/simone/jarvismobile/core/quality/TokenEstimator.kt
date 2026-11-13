package com.simone.jarvismobile.core.quality

/**
 * HOTFIX 5 — a CONSERVATIVE token estimate. The LiteRT-LM Kotlin API (v0.14.0 docs) exposes no tokenizer or
 * token counter, so every budget decision uses this declared estimate instead of a measured count.
 * 3.0 characters/token deliberately over-counts Italian for SentencePiece-style vocabularies (typically
 * ~3.5–4.2), so a prompt that fits the estimate fits the real window with margin. It is an estimate, and the
 * diagnostics label it as such.
 */
object TokenEstimator {
    const val CHARS_PER_TOKEN = 3.0
    /** Per-message framing the chat template adds (role markers, turn delimiters). */
    const val MESSAGE_OVERHEAD = 6

    fun estimate(text: String): Int = if (text.isEmpty()) 0 else Math.ceil(text.length / CHARS_PER_TOKEN).toInt()

    fun message(text: String): Int = estimate(text) + MESSAGE_OVERHEAD
}
