package com.simone.jarvismobile.core.reliability

import com.simone.jarvismobile.core.knowledge.SupportJudge
import com.simone.jarvismobile.core.quality.AnswerQuality

enum class PublicationMode {
    /** Every checkable proposition of the candidate passed: published as written (citations rebuilt). */
    VERIFIED,
    /** Only the propositions that passed are published (summaries/comparisons add verbatim coverage extracts); rejected ones are NOT shown. */
    ALLOWED_PARTS,
    /** Nothing of the candidate passed: the evidence's own relevant sentences are quoted verbatim. */
    FAITHFUL_EXTRACT,
    /** Nothing passed and nothing relevant to quote: a declared limit, no claim. */
    ABSTAIN,
}

enum class PublicationKind { ANSWER, SUMMARY, COMPARE }

/**
 * What the user may see, decided in ONE place for every path that publishes an evidence-based answer.
 * [text] never contains a rejected proposition and its `[n]` markers are rebuilt from verified claim→passage links
 * (the model's own markers are discarded); [sources] are the passages the markers refer to, in marker order ([n] = index+1);
 * [supportive] is false when the listed sources are only "consulted" (ABSTAIN).
 */
data class Publication(
    val text: String,
    val mode: PublicationMode,
    val sources: List<EvidenceItem>,
    val supportive: Boolean,
    val report: ClaimReport,
    val rejected: List<Claim>,
    val reason: String,
) {
    val fullyVerified: Boolean get() = mode == PublicationMode.VERIFIED
    /** Bounded diagnostic: counts and reasons, never the user's or the model's text. */
    fun diagnostic(): String = "pub=${mode.name.lowercase()} supported=${report.supported.size} rejected=${rejected.size} reason=$reason"
}

object PublicationPolicy {
    const val ABSTAIN_TEXT = "Nelle fonti che ho a disposizione non trovo un'informazione verificabile su questo."
    const val PARTIAL_LIMIT = "Il resto non è confermato dalle fonti consultate."
    private const val EXTRACT_RELEVANCE = 0.6
    private const val EXTRACT_SENTENCES = 2
    private const val COVERAGE_ITEMS = 5
    private const val COVERAGE_CHARS = 320
    private val MARKER = Regex("\\s*\\[\\s*(?:E\\s*)?\\d{1,3}\\s*]", RegexOption.IGNORE_CASE)

    /** The candidate without any citation marker or presentation note: citations are rebuilt, never trusted. */
    fun stripCitations(candidate: String): String = AnswerQuality.stripPresentationNotes(MARKER.replace(candidate, "")).trim()

    fun decide(question: String, candidate: String, pkg: EvidencePackage, kind: PublicationKind = PublicationKind.ANSWER): Publication {
        val clean = stripCitations(candidate)
        val report = ClaimVerifier.verify(question, clean, pkg, askRelevance = kind == PublicationKind.ANSWER)
        val supported = report.supported
        val rejected = report.rejected
        val link = SourceLinks(pkg)

        if (supported.isNotEmpty() && rejected.isEmpty()) {
            return Publication(annotate(clean, report.claims, link), PublicationMode.VERIFIED, link.sources, true, report, emptyList(), "tutte le affermazioni verificate")
        }
        if (supported.isNotEmpty()) {
            val body = supported.joinToString(" ") { c -> withMarker(c.text, link.mark(c.evidenceIds)) }
            return if (kind == PublicationKind.ANSWER) {
                Publication("$body $PARTIAL_LIMIT", PublicationMode.ALLOWED_PARTS, link.sources, true, report, rejected, "parti non verificate scartate")
            } else {
                val cover = coverageExtract(pkg, link)
                Publication(listOf(body, cover).filter { it.isNotBlank() }.joinToString("\n") , PublicationMode.ALLOWED_PARTS, link.sources, true, report, rejected, "parti non verificate sostituite da estratti")
            }
        }
        // nothing in the candidate is supported (or it only declared a limit): quote what the sources say, never what the model said
        if (kind != PublicationKind.ANSWER) {
            val cover = coverageExtract(pkg, link)
            if (cover.isNotBlank()) return Publication(cover, PublicationMode.FAITHFUL_EXTRACT, link.sources, true, report, rejected, "candidato non supportato: estratti dai passaggi")
        } else {
            faithfulExtract(question, pkg, link)?.let {
                return Publication(it, PublicationMode.FAITHFUL_EXTRACT, link.sources, true, report, rejected, if (rejected.isEmpty()) "nessuna affermazione verificabile" else "candidato non supportato")
            }
        }
        return Publication(ABSTAIN_TEXT, PublicationMode.ABSTAIN, pkg.items.distinctBy { it.title }, false, report, rejected, "nessun passaggio pertinente")
    }

    /** One revision, with the reason: the rejected propositions are named so the model does not repeat them. */
    fun revisionNote(pub: Publication): String? {
        val bad = pub.rejected.take(3)
        if (bad.isEmpty()) return null
        return "[Controllo: la risposta precedente conteneva affermazioni NON presenti nei passaggi. Non ripeterle: " +
            bad.joinToString(" ") { "«${it.text.take(160)}» (${it.reason})." } +
            " Riscrivi la risposta usando SOLO i passaggi forniti; se non bastano, dillo.]"
    }

    // ---- citations rebuilt from verified links -----------------------------------------------------------

    /** Maps evidence items to marker numbers by SOURCE (title), in order of first citation. */
    private class SourceLinks(private val pkg: EvidencePackage) {
        private val order = ArrayList<EvidenceItem>()
        val citedIds = HashSet<String>()
        val sources: List<EvidenceItem> get() = order.toList()
        fun mark(ids: List<String>): String = ids.also { citedIds += it }.mapNotNull { pkg.item(it) }.map { it.title }.distinct().joinToString("") { t ->
            var idx = order.indexOfFirst { it.title == t }
            if (idx < 0) { order += pkg.items.first { it.title == t }; idx = order.size - 1 }
            "[${idx + 1}]"
        }
    }

    private fun withMarker(text: String, marker: String): String {
        if (marker.isEmpty()) return text
        val t = text.trimEnd()
        val end = t.takeLastWhile { it == '.' || it == '!' || it == '?' }
        return t.dropLast(end.length) + " " + marker + end
    }

    private fun annotate(text: String, claims: List<Claim>, link: SourceLinks): String {
        val sb = StringBuilder()
        var cursor = 0
        for (c in claims) {
            if (c.status != ClaimStatus.EXPLICITLY_SUPPORTED && c.status != ClaimStatus.DERIVED) continue
            val i = text.indexOf(c.text, cursor)
            if (i < 0) continue
            val end = i + c.text.length
            sb.append(text, cursor, i).append(withMarker(c.text, link.mark(c.evidenceIds)))
            cursor = end
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    // ---- verbatim extracts --------------------------------------------------------------------------------

    /** Model unavailable: quote what the sources say about the question, verbatim, with rebuilt markers; null when nothing is pertinent. */
    fun quote(question: String, pkg: EvidencePackage): Publication? {
        val link = SourceLinks(pkg)
        val text = faithfulExtract(question, pkg, link) ?: return null
        return Publication(text, PublicationMode.FAITHFUL_EXTRACT, link.sources, true, ClaimReport(emptyList()), emptyList(), "modello non disponibile")
    }

    /** The evidence sentences that speak about the question, verbatim and in source order; null when none does. */
    private fun faithfulExtract(question: String, pkg: EvidencePackage, link: SourceLinks): String? {
        val q = SupportJudge.stems(question).distinct()
        if (q.isEmpty()) return null
        data class Cand(val itemId: String, val text: String, val cover: Double, val order: Int)
        val cands = ArrayList<Cand>()
        var order = 0
        for (item in pkg.items) {
            val own = SupportJudge.stems(item.title).toSet()
            val asked = q.filter { it !in own }
            if (asked.isEmpty()) {
                Sentences.split(item.text).firstOrNull()?.let { cands += Cand(item.id, it, 1.0, order++) }
                continue
            }
            for (s in Sentences.split(item.text)) {
                val st = SupportJudge.stems(s).toSet()
                val c = asked.count { it in st }.toDouble() / asked.size
                if (c >= EXTRACT_RELEVANCE) cands += Cand(item.id, s, c, order)
                order++
            }
        }
        val top = cands.sortedWith(compareByDescending<Cand> { it.cover }.thenBy { it.order }).take(EXTRACT_SENTENCES).sortedBy { it.order }
        if (top.isEmpty()) return null
        return "Dalle fonti: " + top.joinToString(" ") { withMarker(it.text, link.mark(listOf(it.itemId))) }
    }

    /** Summaries/comparisons: the lead sentence of each passage, verbatim, so coverage survives when paraphrases were rejected. */
    private fun coverageExtract(pkg: EvidencePackage, link: SourceLinks): String {
        val lines = pkg.items.filter { it.id !in link.citedIds }.take(COVERAGE_ITEMS).mapNotNull { item ->
            Sentences.split(item.text).firstOrNull()?.take(COVERAGE_CHARS)?.let { withMarker("${item.title}: $it", link.mark(listOf(item.id))) }
        }
        return if (lines.isEmpty()) "" else "Dai passaggi:\n" + lines.joinToString("\n") { "• $it" }
    }
}
