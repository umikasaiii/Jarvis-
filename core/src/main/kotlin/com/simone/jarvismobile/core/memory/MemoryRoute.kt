package com.simone.jarvismobile.core.memory

/**
 * Where an incoming question should look for grounding, decided before the model
 * runs so the prompt only ever carries what the question actually needs.
 *
 *  - [NONE]            self-contained (arithmetic, greetings): no retrieval.
 *  - [SHORT_TERM]      resolved from the recent conversation recap.
 *  - [PERSONAL_MEMORY] stable facts the user told JARVIS (`Memoria.md`).
 *  - [DOCUMENT_SEARCH] an imported document / attachment.
 *  - [KNOWLEDGE_SEARCH] the offline reference library.
 *  - [CORE_MEMORY]     the `00_CORE/` profile, preferences and rules.
 */
enum class MemoryRoute { NONE, SHORT_TERM, PERSONAL_MEMORY, DOCUMENT_SEARCH, KNOWLEDGE_SEARCH, CORE_MEMORY }

/**
 * A deterministic first-pass classifier. It is intentionally conservative: the
 * signals are surface keywords, and when nothing matches it returns [NONE] so a
 * plain question is answered directly. The Android layer may still escalate an
 * ambiguous case to the one-line LLM classifier, but the common cases resolve
 * here without a model call.
 */
object MemoryRouter {

    fun classify(query: String, hasAttachedDocuments: Boolean = false): MemoryRoute {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return MemoryRoute.NONE

        // A document is attached and the question points at "this file/document".
        if (hasAttachedDocuments && DOC_DEICTIC.any { it in q }) return MemoryRoute.DOCUMENT_SEARCH
        if (DOCUMENT_HINTS.any { it in q }) return MemoryRoute.DOCUMENT_SEARCH

        if (CORE_HINTS.any { it in q }) return MemoryRoute.CORE_MEMORY
        if (PERSONAL_HINTS.any { it in q }) return MemoryRoute.PERSONAL_MEMORY

        if (isArithmetic(q)) return MemoryRoute.NONE
        if (KNOWLEDGE_HINTS.any { it in q }) return MemoryRoute.KNOWLEDGE_SEARCH
        if (SHORT_TERM_HINTS.any { it in q }) return MemoryRoute.SHORT_TERM

        return MemoryRoute.NONE
    }

    private fun isArithmetic(q: String): Boolean {
        val stripped = q.replace(Regex("""[\d\s+\-*/^%().,=]"""), "")
        val hasDigit = q.any { it.isDigit() }
        val hasOp = q.any { it in "+-*/^%" } || "quanto fa" in q || "calcola" in q
        return hasDigit && hasOp && stripped.length <= 6
    }

    private val DOC_DEICTIC = listOf(
        "questo file", "questo documento", "questo pdf", "questo manuale",
        "il file", "il documento", "questo allegato", "l'allegato",
    )
    private val DOCUMENT_HINTS = listOf(
        "nel pdf", "nel documento", "nel file", "nel manuale", "del pdf",
        "cosa dice il", "secondo il documento", "secondo il pdf", "nell'allegato",
        "riassumi il", "riassumi questo", "riassumi il file", "riassumi il documento",
    )
    private val CORE_HINTS = listOf(
        "come mi piace", "le mie preferenze", "mie impostazioni", "come preferisco",
        "il mio profilo", "regole di jarvis", "come devo impostare", "come mi chiamo",
    )
    private val PERSONAL_HINTS = listOf(
        "ti avevo detto", "ti ho detto", "mi ricordi", "ricordami", "ti ricordi",
        "cosa avevo", "qual era", "avevamo deciso", "abbiamo deciso", "avevo scelto",
        "cosa ti ho detto", "il mio ", "la mia ", "i miei ", "le mie ",
    )
    private val KNOWLEDGE_HINTS = listOf(
        "cos'è", "cosa è", "che cos'è", "spiegami", "spiega", "come funziona",
        "definizione", "significa", "differenza tra",
    )
    private val SHORT_TERM_HINTS = listOf(
        "prima", "poco fa", "adesso", "dicevi", "stavi dicendo", "come dicevo",
    )
}
