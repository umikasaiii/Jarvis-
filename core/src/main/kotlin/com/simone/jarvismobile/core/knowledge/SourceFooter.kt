package com.simone.jarvismobile.core.knowledge

/**
 * KNOWLEDGE ENGINE — the "Fonte:" line shown under a grounded answer. Built ONLY from the metadata of the passages that
 * were actually retrieved (title, collection, date, path), never from model output, so a citation can be neither invented
 * nor attached to a source that was not consulted.
 */
object SourceFooter {
    /** A source older than this many years is flagged as possibly out of date. */
    const val STALE_AFTER_YEARS = 8

    fun build(evidence: Evidence.Grounded, currentYear: Int): String {
        val refs = evidence.passages.map { it.chunk }.distinctBy { it.sourceId }
        if (refs.isEmpty()) return ""
        val names = refs.joinToString("; ") { c ->
            val sec = if (c.section.isNotBlank() && c.sourceKind == "document") ", ${c.section}" else ""
            c.reference + sec
        }
        val stale = refs.mapNotNull { yearOf(it.date) }.filter { currentYear - it >= STALE_AFTER_YEARS }.maxOrNull()
        return buildString {
            append("Fonte: ").append(names)
            if (evidence.conflict) append(". Attenzione: le fonti non concordano; la più recente è indicata per prima")
            if (stale != null) append(". Nota: una fonte è del ").append(stale).append(" e potrebbe essere superata")
            append('.')
        }
    }

    private fun yearOf(date: String): Int? = Regex("""(\d{4})""").find(date)?.groupValues?.get(1)?.toIntOrNull()
}
