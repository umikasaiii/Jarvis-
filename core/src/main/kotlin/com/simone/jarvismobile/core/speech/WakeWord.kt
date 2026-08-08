package com.simone.jarvismobile.core.speech

/**
 * Decides whether a heard phrase contains the wake word. Kept in `:core` and
 * pure so it is unit-tested on any JVM — the Android side only feeds it the
 * recognizer's transcript.
 *
 * Matching is whole-word and accent/case-insensitive, so "Ehi, Jarvis!" wakes on
 * "jarvis" while "giravolta" does not. It deliberately does not do fuzzy/phonetic
 * matching yet: a false wake is more annoying than a missed one, and the
 * foreground-only listener can simply be spoken to again.
 */
object WakeWord {

    fun matches(transcript: String, word: String): Boolean {
        val w = normalize(word)
        if (w.isBlank()) return false
        val t = normalize(transcript)
        if (t.isBlank()) return false
        // Unicode-aware boundaries so accented letters next to the word don't
        // fool the match (Java's \b would).
        val boundary = Regex(
            "(?<![\\p{L}\\p{Nd}])" + Regex.escape(w) + "(?![\\p{L}\\p{Nd}])",
        )
        return boundary.containsMatchIn(t)
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        .replace(Regex("[^\\p{L}\\p{Nd} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
