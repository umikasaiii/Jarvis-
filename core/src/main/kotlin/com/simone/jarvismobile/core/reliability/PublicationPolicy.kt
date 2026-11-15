package com.simone.jarvismobile.core.reliability

import com.simone.jarvismobile.core.knowledge.SupportJudge

enum class PublicationMode {
    /** Every checkable sentence of the candidate is supported: published as written. */
    VERIFIED,
    /** Only the supported sentences are published; rejected ones are NOT shown. */
    ALLOWED_PARTS,
    /** Nothing of the candidate was supported: the evidence's own relevant sentences are quoted verbatim. */
    FAITHFUL_EXTRACT,
    /** Nothing supported and nothing relevant to quote: a declared limit, no claim. */
    ABSTAIN,
}

/**
 * What the user is allowed to see, decided in ONE place for every path that publishes a knowledge/document/memory answer.
 * [text] never contains a rejected claim; [citedIds] are the evidence items the PUBLISHED text actually rests on (the caller
 * builds the citation line from them); [rejected] and [reason] feed the bounded diagnostic trace.
 */
data class Publication(
    val text: String,
    val mode: PublicationMode,
    val citedIds: List<String>,
    val report: ClaimReport,
    val rejected: List<Claim>,
    val reason: String,
) {
    val fullyVerified: Boolean get() = mode == PublicationMode.VERIFIED
    fun diagnostic(): String = "pub=${mode.name.lowercase()} supported=${report.supported.size} rejected=${rejected.size} reason=$reason"
}

object PublicationPolicy {
    const val ABSTAIN_TEXT = "Nelle fonti che ho a disposizione non trovo un'informazione verificabile su questo."
    const val PARTIAL_LIMIT = "Il resto non è confermato dalle fonti consultate."
    private const val EXTRACT_RELEVANCE = 0.6
    private const val EXTRACT_SENTENCES = 2

    /**
     * [lenient] is for summaries/comparisons, whose sentences legitimately paraphrase: a claim that is merely not covered by
     * wording (no contradiction, no number/name/attribution problem) is kept, but nothing the evidence contradicts ever is.
     */
    fun decide(question: String, candidate: String, pkg: EvidencePackage, lenient: Boolean = false): Publication {
        val clean = com.simone.jarvismobile.core.quality.AnswerQuality.stripPresentationNotes(pkg.sanitizeReferences(candidate)).trim()
        val strict = ClaimVerifier.verify(question, clean, pkg)
        val report = if (!lenient) strict else ClaimReport(strict.claims.map { c ->
            if (c.status == ClaimStatus.UNVERIFIED && !c.reason.startsWith("numero") && !c.reason.startsWith("nome") && !c.reason.startsWith("attribuzione"))
                c.copy(status = ClaimStatus.DERIVED, reason = "parafrasi (riassunto)", evidenceIds = c.evidenceIds.ifEmpty { pkg.items.take(1).map { it.id } })
            else c
        })
        val supported = report.supported
        val rejected = report.rejected
        if (supported.isNotEmpty() && rejected.isEmpty()) {
            return Publication(clean, PublicationMode.VERIFIED, ids(supported), report, emptyList(), "tutte le affermazioni verificate")
        }
        if (supported.isNotEmpty()) {
            val keep = report.claims.filter { it.status == ClaimStatus.EXPLICITLY_SUPPORTED || it.status == ClaimStatus.DERIVED }
            return Publication(keep.joinToString(" ") { it.text } + " " + PARTIAL_LIMIT, PublicationMode.ALLOWED_PARTS, ids(supported), report, rejected, "parti non verificate scartate")
        }
        // nothing in the candidate is supported (or it only declared a limit): quote what the sources say, never what the model said
        val extract = faithfulExtract(question, pkg)
        if (extract != null) return Publication(extract.first, PublicationMode.FAITHFUL_EXTRACT, extract.second, report, rejected, if (rejected.isEmpty()) "nessuna affermazione verificabile" else "candidato non supportato")
        return Publication(ABSTAIN_TEXT, PublicationMode.ABSTAIN, emptyList(), report, rejected, "nessun passaggio pertinente")
    }

    private fun ids(claims: List<Claim>): List<String> = claims.flatMap { it.evidenceIds }.distinct()

    /** The evidence sentences that speak about the question, verbatim and in source order; null when none does. */
    fun faithfulExtract(question: String, pkg: EvidencePackage): Pair<String, List<String>>? {
        val q = SupportJudge.stems(question).distinct()
        if (q.isEmpty()) return null
        data class Cand(val itemId: String, val text: String, val cover: Double, val order: Int)
        val cands = ArrayList<Cand>()
        var order = 0
        for (item in pkg.items) {
            val own = SupportJudge.stems(item.title).toSet()
            val asked = q.filter { it !in own }
            if (asked.isEmpty()) { // the question only names the subject: the lead sentence of that article answers it
                ClaimVerifier.sentences(item.text).firstOrNull()?.let { cands += Cand(item.id, it, 1.0, order++) }
                continue
            }
            for (s in ClaimVerifier.sentences(item.text)) {
                val st = SupportJudge.stems(s).toSet()
                val c = asked.count { it in st }.toDouble() / asked.size
                if (c >= EXTRACT_RELEVANCE) cands += Cand(item.id, s, c, order)
                order++
            }
        }
        val top = cands.sortedWith(compareByDescending<Cand> { it.cover }.thenBy { it.order }).take(EXTRACT_SENTENCES).sortedBy { it.order }
        if (top.isEmpty()) return null
        return "Dalle fonti: " + top.joinToString(" ") { it.text } to top.map { it.itemId }.distinct()
    }
}
