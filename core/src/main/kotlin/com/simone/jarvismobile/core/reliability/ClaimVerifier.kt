package com.simone.jarvismobile.core.reliability

import com.simone.jarvismobile.core.knowledge.KnowledgeText
import com.simone.jarvismobile.core.knowledge.SupportJudge
import com.simone.jarvismobile.core.quality.AnswerQuality

enum class ClaimStatus {
    /** The claim's content, numbers and polarity are in ONE evidence sentence. */
    EXPLICITLY_SUPPORTED,
    /** Supported by two adjacent sentences of one item, or by an explicit unit conversion. */
    DERIVED,
    /** Nothing in the evidence confirms it (or the attribution of a number/name cannot be confirmed). */
    UNVERIFIED,
    /** The evidence states the opposite (polarity) or a different value for the same thing. */
    CONTRADICTED,
    /** Not a factual statement (connective, hedge, declared limit): neither supported nor rejected. */
    UNKNOWN,
}

data class Claim(val text: String, val status: ClaimStatus, val evidenceIds: List<String>, val reason: String)

data class ClaimReport(val claims: List<Claim>) {
    val supported: List<Claim> get() = claims.filter { it.status == ClaimStatus.EXPLICITLY_SUPPORTED || it.status == ClaimStatus.DERIVED }
    val rejected: List<Claim> get() = claims.filter { it.status == ClaimStatus.UNVERIFIED || it.status == ClaimStatus.CONTRADICTED }
}

/**
 * RELIABILITY — checks each sentence of a candidate answer against the [EvidencePackage] with explicit, inspectable
 * procedures. The answering model is never consulted and lexical relevance ([SupportJudge]) is only used to pick the evidence
 * sentence to compare against; what is compared is:
 *  - numbers + units + decimals in Italian notation ([Quantities]); a different value for the same thing → CONTRADICTED; the same
 *    value attributed to another thing (its neighbouring words share nothing) → UNVERIFIED;
 *  - polarity (negation) of the claim vs the evidence sentence;
 *  - named entities (every one must occur; two entities in reversed order → UNVERIFIED, never "supported");
 *  - content-word coverage (≥ [COVER_SUPPORTED] of the claim in one sentence, or in two adjacent ones for DERIVED).
 * A very short answer ("Lisbona.") is judged together with the question: the evidence sentence must contain it AND speak about
 * what was asked, so a valid-but-irrelevant mention does not support it.
 * Limit, stated plainly: an undecidable paraphrase (different words, same meaning) is UNVERIFIED, not guessed.
 */
object ClaimVerifier {
    const val COVER_SUPPORTED = 0.7
    private const val QUESTION_RELEVANCE = 0.5
    private const val SHORT = 3

    private val NEG = setOf("non", "ne", "mai", "nessun", "nessuna", "nessuno", "senza", "niente", "nulla", "neppure", "nemmeno", "no")
    private val META = Regex("\\b(non (?:ho trovato|trovo|so|ne so|sono in grado|posso confermare|e indicat|risulta|ci sono informazioni)|non (?:e|sono) (?:riportat|present|indicat)|nelle fonti|le fonti|dalle fonti|secondo (?:le|i) )")
    private val CONTEXT_WORDS = 4

    internal class Sent(val title: Set<String>, val itemId: String, val text: String, val folded: String, val stems: Set<String>, val quantities: List<Quantity>, val neg: Boolean, val words: List<String>)

    /** Content stems with the quantities removed: numbers are compared by [Quantities], not by token overlap. */
    internal fun contentStems(text: String): List<String> {
        val q = Quantities.extract(text)
        if (q.isEmpty()) return SupportJudge.stems(text)
        val sb = StringBuilder(text)
        for (x in q) for (i in x.start until minOf(x.end, sb.length)) sb.setCharAt(i, ' ')
        return SupportJudge.stems(sb.toString())
    }

    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim().trimStart('-', '•', '*', '·', ' ').trim() }.filter { it.isNotEmpty() }

    internal fun negated(folded: String): Boolean {
        val w = folded.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return w.indices.any { i -> w[i] in NEG && !(w[i] == "non" && w.getOrNull(i + 1) == "solo") && !(w[i] == "ne" && true) }
    }

    private fun toSent(item: EvidenceItem, text: String): Sent {
        val folded = KnowledgeText.fold(text)
        val titleStems = SupportJudge.stems(item.title + " " + item.section)
        return Sent(titleStems.toSet(), item.id, text, folded, (contentStems(text) + titleStems).toSet(), Quantities.extract(text), negated(folded),
            folded.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() })
    }

    fun verify(question: String, answer: String, pkg: EvidencePackage): ClaimReport {
        val perItem = pkg.items.map { it to sentences(it.text).map { s -> toSent(it, s) } }
        val units = ArrayList<Pair<Sent, Boolean>>() // (window, isPair)
        val windows = ArrayList<Sent>()
        val pairs = ArrayList<Sent>()
        for ((item, ss) in perItem) {
            windows += ss
            for (i in 0 until ss.size - 1) pairs += toSent(item, ss[i].text + " " + ss[i + 1].text)
        }
        val qStems = contentStems(question).toSet()
        val qQuant = Quantities.extract(question)
        val claims = sentences(AnswerQuality.stripPresentationNotes(answer)).map { checkClaim(it, qStems, qQuant, windows, pairs, units) }
        return ClaimReport(claims)
    }

    private fun checkClaim(text: String, qStems: Set<String>, qQuant: List<Quantity>, windows: List<Sent>, pairs: List<Sent>, @Suppress("UNUSED_PARAMETER") u: Any): Claim {
        val folded = KnowledgeText.fold(text)
        val stems = contentStems(text).distinct()
        val quants = Quantities.extract(text).filter { q -> qQuant.none { Quantities.compare(it, q) != Quantities.Match.DIFFERENT } }
        if (META.containsMatchIn(folded) || (stems.isEmpty() && quants.isEmpty())) return Claim(text, ClaimStatus.UNKNOWN, emptyList(), "non fattuale")
        val short = stems.size < SHORT
        val negC = negated(folded)

        fun cover(s: Sent) = if (stems.isEmpty()) 1.0 else stems.count { it in s.stems }.toDouble() / stems.size
        // a short answer must sit in a sentence that speaks about what was asked; the article's own title is implied, so
        // only the OTHER question terms can show relevance
        fun relevant(s: Sent): Boolean {
            val asked = qStems.filter { it !in s.title }
            return asked.isEmpty() || asked.count { it in s.stems }.toDouble() / asked.size >= QUESTION_RELEVANCE
        }
        // best single sentence: highest claim coverage; for short answers it must also be about the question
        val single = windows.filter { !short || relevant(it) }.maxByOrNull { cover(it) }
        val pair = if (short) null else pairs.maxByOrNull { cover(it) }
        val best = single
        val bestCover = best?.let(::cover) ?: 0.0
        val pairCover = pair?.let(::cover) ?: 0.0
        val explicit = best != null && bestCover >= COVER_SUPPORTED && (!short || bestCover >= 1.0)
        val derived = !explicit && pair != null && pairCover >= COVER_SUPPORTED && (!short || pairCover >= 1.0)
        if (!explicit && !derived) {
            // contradiction with a near-identical sentence: same words, opposite polarity or a different value
            val near = (windows + pairs).maxByOrNull { cover(it) }
            if (near != null && cover(near) >= COVER_SUPPORTED) judge(text, negC, quants, near, true)?.let { return it }
            return Claim(text, ClaimStatus.UNVERIFIED, emptyList(), if ((near?.let(::cover) ?: 0.0) < 0.4) "nessun passaggio lo conferma" else "supporto parziale")
        }
        val win = if (explicit) best!! else pair!!
        judge(text, negC, quants, win, false)?.let { return it }

        // entities: each must occur in the evidence window; two entities must keep their order
        val allEntities = capitalised(text).map { KnowledgeText.fold(it) }.filter { SupportJudge.stems(it).isNotEmpty() }.distinct()
        val entities = allEntities
        for (e in allEntities.filter { e -> SupportJudge.stems(e).first() !in qStems }) if (e !in win.folded) return Claim(text, ClaimStatus.UNVERIFIED, emptyList(), "nome non presente nel passaggio: $e")
        if (entities.size >= 2) {
            val orderC = entities.map { folded.indexOf(it) }
            val orderS = entities.map { win.folded.indexOf(it) }
            if (orderC.zipWithNext().all { (a, b) -> a < b } != orderS.zipWithNext().all { (a, b) -> a < b }) {
                return Claim(text, ClaimStatus.UNVERIFIED, emptyList(), "attribuzione invertita rispetto al passaggio")
            }
        }
        val converted = quants.any { q -> win.quantities.none { Quantities.compare(q, it) == Quantities.Match.EXACT } }
        return Claim(text, if (explicit && !converted) ClaimStatus.EXPLICITLY_SUPPORTED else ClaimStatus.DERIVED, listOf(win.itemId), if (converted) "conversione di unità" else "")
    }

    /** CONTRADICTED / UNVERIFIED verdicts on the numbers and polarity of [win], or null when they raise nothing. */
    private fun judge(text: String, negC: Boolean, quants: List<Quantity>, win: Sent, nearOnly: Boolean): Claim? {
        if (negC != win.neg) return Claim(text, ClaimStatus.CONTRADICTED, listOf(win.itemId), "polarità opposta al passaggio")
        if (nearOnly) { /* a near sentence is only used to find contradictions; numbers still checked below */ }
        for (q in quants) {
            val hits = win.quantities.map { it to Quantities.compare(q, it) }
            val ok = hits.filter { it.second != Quantities.Match.DIFFERENT }
            val ctxC = context(text, q.start, q.end)
            if (ok.isNotEmpty()) {
                if (win.quantities.size > 1 && misattributed(ctxC, ok.map { it.first }, win)) {
                    return Claim(text, ClaimStatus.UNVERIFIED, listOf(win.itemId), "numero ${q.raw} attribuito a un altro elemento")
                }
                continue
            }
            val sameKind = win.quantities.filter { it.dimension == q.dimension && (q.dimension != null || context(win.text, it.start, it.end).any { w -> w in ctxC }) }
            if (sameKind.isNotEmpty()) return Claim(text, ClaimStatus.CONTRADICTED, listOf(win.itemId), "valore diverso: ${q.raw} vs ${sameKind.first().raw}")
            return Claim(text, ClaimStatus.UNVERIFIED, listOf(win.itemId), "numero ${q.raw} non nel passaggio")
        }
        return if (nearOnly) null else null
    }

    /** True when the claim's words around the number sit, in the evidence, next to a DIFFERENT quantity and never next to the matched one. */
    private fun misattributed(ctx: Set<String>, matched: List<Quantity>, win: Sent): Boolean {
        var forMatched = 0; var forOthers = 0
        for (st in ctx) {
            val w = win.words.firstOrNull { SupportJudge.stems(it).firstOrNull() == st } ?: continue
            val pos = win.folded.indexOf(w)
            val nearest = win.quantities.minByOrNull { minOf(kotlin.math.abs(it.start - pos), kotlin.math.abs(it.end - pos)) } ?: continue
            if (nearest in matched) forMatched++ else forOthers++
        }
        return forOthers > 0 && forMatched == 0
    }

    private fun capitalised(text: String): List<String> =
        Regex("\\p{Lu}[\\p{L}\\d'’-]{2,}").findAll(text).map { it.value.substringAfter('\'').substringAfter('’') }.filter { it.length >= 3 }.toList()

    private fun context(text: String, start: Int, end: Int): Set<String> {
        val before = SupportJudge.stems(text.substring(0, start).takeLast(60)).takeLast(CONTEXT_WORDS)
        val after = SupportJudge.stems(text.substring(end).take(60)).take(CONTEXT_WORDS)
        return (before + after).toSet()
    }
}
