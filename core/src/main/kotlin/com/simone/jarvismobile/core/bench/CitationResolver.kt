package com.simone.jarvismobile.core.bench

/**
 * Reconstructs the OBSERVED citations of a published answer from its footer, using only what the system itself exposed (labels and retrieved passage ids).
 * It never reads gold fields (`expectedCitations`, `supportingEvidence`): a footer that names only an article cannot identify a section, so when several
 * retrieved passages share that label the citation is AMBIGUOUS (not verifiable) and is not assigned to any passage.
 */
object CitationResolver {
    data class Candidate(val passageId: String, val label: String)
    data class Resolved(val passageIds: List<String>, val ambiguous: Int, val unresolved: Int)

    private val MARKER = Regex("""^\[\d+]\s*""")
    private val PASSAGE_HINT = Regex("""\(brano E(\d+)\)""")

    /** [footerLines] are the `[n] label` lines; [candidates] the retrieved passages in retrieval order (E1 = first). */
    fun resolve(footerLines: List<String>, candidates: List<Candidate>): Resolved {
        val out = LinkedHashSet<String>(); var ambiguous = 0; var unresolved = 0
        for (raw in footerLines) {
            val label = raw.trim().replace(MARKER, "")
            val hinted = PASSAGE_HINT.find(label)?.groupValues?.get(1)?.toIntOrNull()
            if (hinted != null) { candidates.getOrNull(hinted - 1)?.let { out += it.passageId } ?: run { unresolved++ }; continue }
            val matching = candidates.filter { label.startsWith(it.label.substringBefore(" (")) }.map { it.passageId }.distinct()
            when (matching.size) { 0 -> unresolved++; 1 -> out += matching[0]; else -> ambiguous++ }
        }
        return Resolved(out.toList(), ambiguous, unresolved)
    }
}
