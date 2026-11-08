package com.simone.jarvismobile.core.translate

/**
 * Drops a recognizer result that is really JARVIS's own just-spoken translation coming back in through
 * the microphone, and an identical result repeated back-to-back. Pure and time-injected.
 */
class EchoGuard(
    private val windowMs: Long = 8_000,
    private val threshold: Double = 0.7,
    private val duplicateWindowMs: Long = 3_000,
) {
    private var spoken: Set<String> = emptySet()
    private var spokenAt = Long.MIN_VALUE
    private var lastSource: String? = null
    private var lastSourceAt = Long.MIN_VALUE

    fun reset() { spoken = emptySet(); spokenAt = Long.MIN_VALUE; lastSource = null; lastSourceAt = Long.MIN_VALUE }

    /** Call when a translation has been (or was being) read aloud. */
    fun recordSpoken(text: String, now: Long) { spoken = grams(text); spokenAt = now }

    /** True if [heard] should be ignored. A non-echo result is remembered for the duplicate check. */
    fun shouldDrop(heard: String, now: Long): Boolean {
        val norm = normalize(heard)
        if (norm.isEmpty()) return true
        if (spoken.isNotEmpty() && now - spokenAt <= windowMs) {
            val g = grams(heard)
            if (g.isNotEmpty() && g.intersect(spoken).size.toDouble() / minOf(g.size, spoken.size) >= threshold) return true
        }
        if (norm == lastSource && now - lastSourceAt <= duplicateWindowMs) return true
        lastSource = norm; lastSourceAt = now
        return false
    }

    private fun normalize(t: String) = t.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim().replace(Regex("\\s+"), " ")

    // Words for spaced languages, character bigrams for scripts without spaces (Japanese).
    private fun grams(t: String): Set<String> {
        val n = normalize(t)
        val words = n.split(' ').filter { it.isNotEmpty() }
        return if (words.size >= 2 || n.none { it.code in 0x3040..0x9FFF }) words.toSet()
        else n.replace(" ", "").windowed(2, 1, partialWindows = false).toSet().ifEmpty { setOf(n) }
    }
}
