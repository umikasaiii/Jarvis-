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
        return Publication(ABSTAIN_TEXT, PublicationMode.ABSTAIN, pkg.items, false, report, rejected, "nessun passaggio pertinente")
    }

    /** Footer labels that stay distinguishable when several passages share a title: the passage id is appended to duplicates only. */
    fun distinctLabels(labels: List<String>, items: List<EvidenceItem>): List<String> =
        labels.mapIndexed { i, l -> if (labels.count { it == l } > 1) "$l (brano ${items[i].id})" else l }

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
        /** Markers are per PASSAGE: two passages that share a title (other section, other document, other version) stay distinct. */
        fun mark(ids: List<String>): String = ids.also { citedIds += it }.distinct().mapNotNull { pkg.item(it) }.joinToString("") { item ->
            var idx = order.indexOfFirst { it.id == item.id }
            if (idx < 0) { order += item; idx = order.size - 1 }
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

    /**
     * Summaries/comparisons: per passage, the first sentence that fits [COVERAGE_CHARS] COMPLETE, verbatim. A sentence is never cut:
     * one that does not fit is skipped (a later whole sentence of the same passage may be used), and if a passage has none, its
     * omission is declared so a partial extract is never read as the full statement.
     */
    private fun coverageExtract(pkg: EvidencePackage, link: SourceLinks): String {
        var skipped = 0
        val lines = pkg.items.filter { it.id !in link.citedIds }.take(COVERAGE_ITEMS).mapNotNull { item ->
            val whole = Sentences.split(item.text).firstOrNull { it.length <= COVERAGE_CHARS }
            if (whole == null) { skipped++; null } else withMarker("${item.title}: $whole", link.mark(listOf(item.id)))
        }
        if (lines.isEmpty() && skipped == 0) return ""
        val note = if (skipped > 0) "\n(Altri $skipped passaggi hanno frasi troppo lunghe per essere riportate per intero: non sono stati riassunti.)" else ""
        return "Dai passaggi:\n" + lines.joinToString("\n") { "• $it" } + note
    }

    /**
     * A tool receipt restated by the model: published as written unless it states a number or a name the receipt does not contain;
     * then the receipt itself is rendered, verbatim. No new generation, no second verification engine.
     */
    fun decideToolOnly(question: String, candidate: String, receipt: EvidencePackage): Publication {
        val clean = stripCitations(candidate)
        val report = ClaimVerifier.verifyFigures(question, clean, receipt)
        if (report.rejected.isEmpty()) return Publication(clean, PublicationMode.VERIFIED, emptyList(), true, report, emptyList(), "dati conformi alla ricevuta")
        val rendered = receipt.items.joinToString("\n") { it.text }
        return Publication(rendered, PublicationMode.FAITHFUL_EXTRACT, emptyList(), true, report, report.rejected, "dati non presenti nella ricevuta: reso il risultato dello strumento")
    }
}
