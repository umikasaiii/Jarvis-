package com.simone.jarvismobile.core.reliability

/** What retrieval concluded for a request that carries no verifiable evidence package. */
enum class RetrievalState {
    /** Nothing relevant exists (or no library is configured). */
    ABSENT,
    /** Relevant sources exist but none contains the answer (or they are only related to the topic). */
    INSUFFICIENT,
    /** Reading the sources FAILED: this is not the same as "the information does not exist". */
    ERROR,
    /** No offline source is configured/available at all. (Same wording as [ABSENT]; kept apart so callers can tell the cases.) */
    UNAVAILABLE,
    /** Sources exist and were searched, nothing pertinent came back. */
    NO_RESULT,
    /** Pertinent sources WERE retrieved but did not fit the context window in this request: not "no information", "not used". */
    EXCLUDED_BY_BUDGET,
}

/**
 * RELIABILITY — how an answer WITHOUT supporting evidence is introduced. The qualifier comes BEFORE the content (a reader never
 * meets the claim first and a disclaimer after it), only factual requests get one, and model knowledge never receives citations
 * or verification labels it does not have. Conversation, text transformation, calculations and hypotheticals are left untouched.
 */
object AnswerFraming {
    const val NO_ANSWER_IN_SOURCES = "Nelle fonti offline non c'è la risposta: quanto segue viene dalla conoscenza generale del modello e non è verificato nelle fonti."
    const val SOURCES_UNREADABLE = "Non sono riuscito a leggere le fonti offline (errore di lettura): quanto segue viene dalla conoscenza generale del modello e non è verificato."

    const val EXCLUDED = "Ho trovato fonti offline pertinenti ma in questa richiesta non ho potuto usarle (limite del contesto del modello): quanto segue viene dalla conoscenza generale e non è verificato. Prova a ripetere la domanda in modo più breve."
    const val NO_RELEVANT_SOURCE = "Nelle fonti offline non ho trovato nulla di pertinente: quanto segue viene dalla conoscenza generale del modello e non è verificato."
    const val NO_SOURCES = "Non ho trovato fonti offline su questo: quanto segue viene dalla conoscenza generale del modello e non è verificato."

    /**
     * A precise, checkable statement: a number, a date, or a proper name the question did not contain. General explanations
     * without such values are not tagged when no source exists (that would be a blanket disclaimer, i.e. a universal abstention).
     */
    fun statesPreciseFacts(question: String, answer: String): Boolean {
        if (Quantities.extract(answer).isNotEmpty()) return true
        val asked = question.lowercase()
        return Regex("(?<![.!?]\\s)(?<!^)\\b\\p{Lu}[\\p{L}]{2,}").findAll(answer).any { it.value.lowercase() !in asked }
    }

    fun qualifier(kind: RequestKind, state: RetrievalState, preciseFacts: Boolean = true): String = when {
        kind != RequestKind.FACTUAL -> ""
        state == RetrievalState.ERROR -> SOURCES_UNREADABLE
        state == RetrievalState.INSUFFICIENT -> NO_ANSWER_IN_SOURCES
        state == RetrievalState.EXCLUDED_BY_BUDGET -> EXCLUDED // always: the user must know the sources were not used, precise facts or not
        (state == RetrievalState.ABSENT || state == RetrievalState.UNAVAILABLE) && preciseFacts -> NO_SOURCES
        state == RetrievalState.NO_RESULT && preciseFacts -> NO_RELEVANT_SOURCE
        else -> ""
    }

    fun frame(kind: RequestKind, state: RetrievalState, answer: String, question: String = ""): String {
        val q = qualifier(kind, state, statesPreciseFacts(question, answer))
        return if (q.isEmpty()) answer else q + "\n" + answer
    }
}
