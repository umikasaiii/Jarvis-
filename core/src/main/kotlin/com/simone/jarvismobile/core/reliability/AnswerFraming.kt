package com.simone.jarvismobile.core.reliability

/** What retrieval concluded for a request that carries no verifiable evidence package. */
enum class RetrievalState {
    /** Nothing relevant exists (or no library is configured). */
    ABSENT,
    /** Relevant sources exist but none contains the answer (or they are only related to the topic). */
    INSUFFICIENT,
    /** Reading the sources FAILED: this is not the same as "the information does not exist". */
    ERROR,
}

/**
 * RELIABILITY — how an answer WITHOUT supporting evidence is introduced. The qualifier comes BEFORE the content (a reader never
 * meets the claim first and a disclaimer after it), only factual requests get one, and model knowledge never receives citations
 * or verification labels it does not have. Conversation, text transformation, calculations and hypotheticals are left untouched.
 */
object AnswerFraming {
    const val NO_ANSWER_IN_SOURCES = "Nelle fonti offline non c'è la risposta: quanto segue viene dalla conoscenza generale del modello e non è verificato nelle fonti."
    const val SOURCES_UNREADABLE = "Non sono riuscito a leggere le fonti offline (errore di lettura): quanto segue viene dalla conoscenza generale del modello e non è verificato."

    fun qualifier(kind: RequestKind, state: RetrievalState): String = when {
        kind != RequestKind.FACTUAL -> ""
        state == RetrievalState.ERROR -> SOURCES_UNREADABLE
        state == RetrievalState.INSUFFICIENT -> NO_ANSWER_IN_SOURCES
        else -> ""
    }

    fun frame(kind: RequestKind, state: RetrievalState, answer: String): String {
        val q = qualifier(kind, state)
        return if (q.isEmpty()) answer else q + "\n" + answer
    }
}
