package com.simone.jarvismobile.core.llm

/**
 * HOTFIX 3 — recognises the degenerate generations a small quantised model produces when its native state
 * is damaged (observed on the Honor 200: «212121» as the reply to unrelated questions). Pure and cheap.
 * It classifies SHAPE only (periodic short unit / looping tokens); it never inspects meaning, so it cannot
 * censor a legitimate answer except an entire reply that is nothing but a tiny repeating pattern.
 */
object DegenerateOutput {
    enum class Reason { NONE, PERIODIC_SHORT, TOKEN_LOOP }

    data class Verdict(val degenerate: Boolean, val reason: Reason, val unitLength: Int) {
        companion object { val OK = Verdict(false, Reason.NONE, 0) }
    }

    fun analyze(text: String): Verdict {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.length >= MIN_CHARS) {
            for (period in 1..MAX_PERIOD) {
                if (compact.length < period * MIN_REPEATS) continue
                if (isPeriodic(compact, period)) {
                    val hasLetters = compact.any { it.isLetter() }
                    // Letter patterns need more evidence than digit/punctuation ones ("ha ha ha" is fine).
                    if (!hasLetters || compact.length >= MIN_CHARS_LETTERS) {
                        return Verdict(true, Reason.PERIODIC_SHORT, period)
                    }
                }
            }
        }
        val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        for (unit in 1..MAX_TOKEN_UNIT) {
            if (tokens.size < maxOf(MIN_TOKENS, unit * MIN_REPEATS)) continue
            if (tokens.indices.all { it < unit || tokens[it] == tokens[it - unit] }) {
                return Verdict(true, Reason.TOKEN_LOOP, unit)
            }
        }
        return Verdict.OK
    }

    private fun isPeriodic(s: String, period: Int): Boolean {
        for (i in period until s.length) if (s[i] != s[i - period]) return false
        return true
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_CHARS = 6
    private const val MIN_CHARS_LETTERS = 12
    private const val MAX_PERIOD = 4
    private const val MIN_REPEATS = 3
    private const val MIN_TOKENS = 6
    private const val MAX_TOKEN_UNIT = 3
}
