package com.simone.jarvismobile.core.quality

/**
 * A DECLARED ESTIMATE of the token count, not a measurement. The LiteRT-LM Kotlin API of the version JARVIS ships (0.14.0) has no tokenizer or
 * counter that has been verified (see docs/RELIABILITY.md, "Tokenizer"), so budget decisions use this heuristic. It is deliberately pessimistic
 * per character class — letters and spaces about 3 per token, every digit, punctuation mark and symbol one token each, every non-Latin
 * character one token — but it is a guess about a tokenizer we cannot call: it is NOT a proven upper bound for any language or content, and
 * every figure derived from it is labelled "stima" wherever it is shown. The window itself is a parameter of [ContextBudget], not a constant.
 */
object TokenEstimator {
    /** Letters/whitespace per token for ordinary Latin prose (Italian, English…). */
    const val CHARS_PER_TOKEN = 3.0
    /** Per-message framing the chat template adds (role markers, turn delimiters). */
    const val MESSAGE_OVERHEAD = 6

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var prose = 0
        var heavy = 0 // digits, punctuation, symbols, non-Latin characters: counted one token each
        for (c in text) {
            when {
                c.isWhitespace() -> prose++
                c.isDigit() -> heavy++
                c.isLetter() && c.code < 0x250 -> prose++ // Latin incl. accents
                else -> heavy++
            }
        }
        return Math.ceil(prose / CHARS_PER_TOKEN).toInt() + heavy
    }

    fun message(text: String): Int = estimate(text) + MESSAGE_OVERHEAD
}
