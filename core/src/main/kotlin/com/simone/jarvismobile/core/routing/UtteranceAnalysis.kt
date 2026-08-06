package com.simone.jarvismobile.core.routing

/**
 * Splits only explicit, independently punctuated questions.
 *
 * A model—especially a small on-device one—often answers only the first part of
 * "Che ore sono? Quanto fa 5+6?". Keeping this operation deterministic lets the
 * coordinator route every part separately without trying to guess where clauses
 * end inside a normal sentence.
 */
object CompoundRequestSplitter {
    fun split(text: String, maxParts: Int = 4): List<String> {
        val input = text.trim()
        if (input.isEmpty()) return emptyList()
        if ('?' !in input) return listOf(input)

        val parts = ArrayList<String>()
        var start = 0
        for (index in input.indices) {
            if (input[index] != '?') continue
            val part = input.substring(start, index + 1).trim()
            if (part.any(Char::isLetterOrDigit)) parts += part
            start = index + 1
        }
        val tail = input.substring(start).trim()
        if (tail.any(Char::isLetterOrDigit)) parts += tail

        if (parts.size <= 1) return listOf(input)
        if (parts.size <= maxParts.coerceAtLeast(2)) return parts

        val limit = maxParts.coerceAtLeast(2)
        return parts.take(limit - 1) + parts.drop(limit - 1).joinToString(" ")
    }
}

/**
 * Conservative fallback for selecting the larger local model.
 *
 * The fast model remains the primary classifier, but a malformed/empty
 * classifier reply must not force an obviously explanatory or comparative
 * question onto the 1B model.
 */
object ComplexityHeuristic {
    private val reasoningSignals = listOf(
        "perche", "come si ", "come posso", "in che modo", "spiega", "analizza",
        "confront", "differenz", "vantaggi", "svantaggi", "pro e contro",
        "quale conviene", "cosa serve", "di quali attrezz", "passo passo",
        "procedura", "consigli", "valuta", "stima", "indicativ",
        "quanto tempo richied", "quanto mi richied",
    )

    fun needsReasoning(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isBlank()) return false
        if (CompoundRequestSplitter.split(text).size > 1) return true
        if (reasoningSignals.any(normalized::contains)) return true
        return normalized.split(Regex("""\s+""")).count { it.isNotBlank() } >= 22
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
}
