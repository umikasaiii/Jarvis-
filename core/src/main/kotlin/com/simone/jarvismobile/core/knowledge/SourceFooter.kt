package com.simone.jarvismobile.core.knowledge

/**
 * KNOWLEDGE ENGINE 2.0 — the source line(s) shown under a knowledge answer. Built ONLY from the metadata of passages that were
 * actually retrieved (title, collection, date), never from model output — and worded by the grounding state, so a source is
 * never presented as support unless it supports the answer:
 *  - CLAIM_SUPPORTED          → "Fonte: …" (only the passages whose window holds the answer's values);
 *  - SOURCE_FOUND             → "Fonti consultate (non confermano la risposta): …";
 *  - INSUFFICIENT_EVIDENCE    → "Fonti offline consultate, senza una risposta: …";
 *  - NO_EVIDENCE              → nothing.
 */
object SourceFooter {
    /** A source older than this many years is flagged as possibly out of date. */
    const val STALE_AFTER_YEARS = 8
    const val SUPPORTED_PREFIX = "Fonte: "
    const val CONSULTED_PREFIX = "Fonti consultate (non confermano la risposta): "
    const val INSUFFICIENT_PREFIX = "Fonti offline consultate, senza una risposta: "

    fun build(evidence: Evidence.Grounded, currentYear: Int): String =
        forPassages(SUPPORTED_PREFIX, evidence.passages.map { it.chunk }, evidence.conflict, currentYear)

    /** The line for a [GroundingVerifier.Verdict]; empty when nothing may be cited. */
    fun build(verdict: GroundingVerifier.Verdict, evidence: Evidence?, currentYear: Int): String = when (verdict.state) {
        GroundingState.CLAIM_SUPPORTED ->
            forPassages(SUPPORTED_PREFIX, verdict.supporting.map { it.chunk }, (evidence as? Evidence.Grounded)?.conflict == true, currentYear)
        GroundingState.SOURCE_FOUND ->
            forPassages(CONSULTED_PREFIX, (evidence as? Evidence.Grounded)?.passages?.map { it.chunk }.orEmpty(), false, currentYear)
        GroundingState.INSUFFICIENT_EVIDENCE ->
            forPassages(INSUFFICIENT_PREFIX, (evidence as? Evidence.Insufficient)?.consulted.orEmpty(), false, currentYear)
        GroundingState.NO_EVIDENCE -> ""
    }

    private fun forPassages(prefix: String, chunks: List<KnowledgeChunk>, conflict: Boolean, currentYear: Int): String {
        val refs = chunks.distinctBy { it.sourceId }
        if (refs.isEmpty()) return ""
        val names = refs.joinToString("; ") { c ->
            val sec = if (c.section.isNotBlank() && c.sourceKind == "document") ", ${c.section}" else ""
            c.reference + sec
        }
        val stale = refs.mapNotNull { yearOf(it.date) }.filter { currentYear - it >= STALE_AFTER_YEARS }.maxOrNull()
        return buildString {
            append(prefix).append(names)
            if (conflict) append(". Attenzione: le fonti non concordano; la più recente è indicata per prima")
            if (stale != null) append(". Nota: una fonte è del ").append(stale).append(" e potrebbe essere superata")
            append('.')
        }
    }

    private fun yearOf(date: String): Int? = Regex("""(\d{4})""").find(date)?.groupValues?.get(1)?.toIntOrNull()
}
