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
    /** "document" (a file in the library folder) or "zim" (an article of an encyclopaedia archive). */
    val sourceKind: String = "document",
    /** Human name of the container, e.g. the archive title ("Wikipedia (it)"), or empty for a plain document. */
    val collection: String = "",
    /** Publication/build date of the source when known (ISO "2024-06-18"), else empty. Lets stale sources be named as such. */
    val date: String = "",
) {
    /** How JARVIS names this passage out loud: "Manuale CB500, Cambio gomme". */
    val citation: String
        get() = if (section.isBlank()) sourceTitle else "$sourceTitle, $section"

    /** A verifiable reference to where the passage came from: collection + date when known. */
    val reference: String
        get() = buildString {
            append(sourceTitle)
            val tail = listOf(collection, date).filter { it.isNotBlank() }
            if (tail.isNotEmpty()) append(" (").append(tail.joinToString(", ")).append(')')
        }
}

/** A chunk with the score it earned for a question. */
data class ScoredChunk(val chunk: KnowledgeChunk, val score: Double)

/**
 * What the retrieval layer concluded for one question. [Grounded] carries real
 * passages; [NoEvidence] means the library has nothing good enough — and saying
 * so is a correct answer, not a failure.
 */
sealed interface Evidence {
    /**
     * [strength] says HOW sufficient the evidence is (see [EvidenceAssembler]); [conflict] is true when two sources
     * state different numbers for the same subject; [titleMatched] when at least one passage comes from an article
     * whose TITLE the question named (entity grounding), not merely from lexical similarity.
     */
    data class Grounded(
        val passages: List<ScoredChunk>,
        val strength: EvidenceStrength = EvidenceStrength.STRONG,
        val conflict: Boolean = false,
        val titleMatched: Boolean = false,
        /** Proper nouns of the question that NO retrieved passage covers: the answer must say there is no source for them. */
        val uncovered: List<String> = emptyList(),
    ) : Evidence
    data object NoEvidence : Evidence
}

enum class EvidenceStrength { STRONG, MODERATE }
