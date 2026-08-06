package com.simone.jarvismobile.core.knowledge

/**
 * One passage of reference knowledge: an encyclopaedia article section, a page
 * of a manual, a paragraph of a downloaded guide.
 *
 * Reference knowledge is deliberately a different type from personal memory
 * (`core.memory`). Memory is what Simone told JARVIS about himself; knowledge is
 * evidence from a document. They must never be mixed: a Wikipedia sentence is
 * not something Simone said, and copying it into `JARVIS/Memoria.md` would
 * corrupt the personal record (docs/LOCAL_KNOWLEDGE.md).
 */
data class KnowledgeChunk(
    /** Stable identifier of the document, e.g. `guide:moto-cb500.md`. */
    val sourceId: String,
    /** Human-readable document name, shown when JARVIS cites the passage. */
    val sourceTitle: String,
    /** Heading/page the passage came from, or empty. */
    val section: String,
    val text: String,
) {
    /** How JARVIS names this passage out loud: "Manuale CB500, Cambio gomme". */
    val citation: String
        get() = if (section.isBlank()) sourceTitle else "$sourceTitle, $section"
}

/** A chunk with the score it earned for a question. */
data class ScoredChunk(val chunk: KnowledgeChunk, val score: Double)

/**
 * What the retrieval layer concluded for one question. [Grounded] carries real
 * passages; [NoEvidence] means the library has nothing good enough — and saying
 * so is a correct answer, not a failure.
 */
sealed interface Evidence {
    data class Grounded(val passages: List<ScoredChunk>) : Evidence
    data object NoEvidence : Evidence
}
