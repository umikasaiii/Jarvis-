package com.simone.jarvismobile.core.reliability

import com.simone.jarvismobile.core.knowledge.KnowledgeText
import com.simone.jarvismobile.core.knowledge.SupportJudge
import com.simone.jarvismobile.core.quality.AnswerQuality

enum class ClaimStatus {
    /** One evidence sentence matches the claim's words AND passes every consistency check (numbers, units, sign, polarity, names, qualifiers). */
    EXPLICITLY_SUPPORTED,
    /** Supported through an explicit, checkable transformation (a unit conversion). Two nearby sentences are NOT a derivation. */
    DERIVED,
    /** Nothing in the evidence confirms it, or one consistency check could not be passed. */
    UNVERIFIED,
    /** The evidence states the opposite (polarity) or a different value for the same thing. */
    CONTRADICTED,
    /** Not a factual proposition: a connective or a declared limit. Neither supported nor rejected. */
    UNKNOWN,
}

/** [text] is the clause as written (used to rebuild the published text); [evidenceIds] are the items it was checked against. */
data class Claim(val text: String, val status: ClaimStatus, val evidenceIds: List<String>, val reason: String)

data class ClaimReport(val claims: List<Claim>) {
    val supported: List<Claim> get() = claims.filter { it.status == ClaimStatus.EXPLICITLY_SUPPORTED || it.status == ClaimStatus.DERIVED }
    val rejected: List<Claim> get() = claims.filter { it.status == ClaimStatus.UNVERIFIED || it.status == ClaimStatus.CONTRADICTED }
}

/**
 * RELIABILITY — checks each proposition of a candidate answer against the [EvidencePackage] with explicit, inspectable
 * procedures. Roles, stated precisely:
 *  - lexical overlap ([SupportJudge] stems) only SELECTS the evidence sentence to compare against and flags anomalies; it is NOT
 *    proof of implication;
 *  - what decides is a set of consistency checks on that sentence: numbers+units+sign+decimals in Italian notation
 *    ([Quantities]), value↔thing attribution, polarity (negation), named entities and their order, qualifiers ("circa",
 *    "solo", "può", "se"…) preserved, universal quantifiers not added, and file/page references that exist in the evidence;
 *  - "secondo le fonti, X" is checked as X: the attribution phrase does not make X non-factual;
 *  - a short answer ("Lisbona.") must sit in a sentence that speaks about what was asked;
 *  - DERIVED requires a transformation that is itself checked (unit conversion), never mere proximity of two sentences.
 * Limit, stated plainly: this is not natural-language inference. A correct paraphrase with different words, or a claim whose
 * support needs reasoning, is UNVERIFIED — the policy then publishes supported parts or a verbatim extract, never a guess.
 */
object ClaimVerifier {
    const val COVER_SUPPORTED = 0.7
    private const val QUESTION_RELEVANCE = 0.5
    private const val SHORT = 3
    private const val CONTEXT_WORDS = 3
    private const val MAX_CANDIDATES = 6

    private val NEG = setOf("non", "mai", "nessun", "nessuna", "nessuno", "senza", "niente", "nulla", "neppure", "nemmeno", "no")
    private val HEDGES = setOf(
        "circa", "quasi", "almeno", "oltre", "fino", "soltanto", "solo", "tranne", "eccetto", "spesso", "talvolta", "generalmente",
        "solitamente", "puo", "possono", "potrebbe", "probabilmente", "forse", "alcuni", "alcune", "se", "qualora",
    )
    private val UNIVERSALS = setOf("sempre", "tutti", "tutte", "mai", "ogni", "esclusivamente", "unico", "unica", "unicamente")
    private val LIMIT = Regex("\\b(non (?:ho trovato|trovo|so|ne so|lo so|dispongo|ho (?:altre |ulteriori |piu )?(?:informazioni|dati|elementi|notizie)|sono in grado|posso (?:confermare|verificare|dirti|dire)|ci sono (?:altre )?informazioni|e possibile verificare)|nelle fonti non|le fonti non|non e (?:indicat|riportat|present|specificat)\\w* nelle fonti|non risulta nelle fonti)")
    private val ATTRIBUTION = Regex("^(?:secondo|stando a|in base a|come (?:indicato|riportato|scritto) (?:da|nel|nei|nelle|dalle|dai))\\s+(?:le|i|gli|il|la|dalle|nelle)?\\s*(?:fonti|font[ei]|document\\w*|brani|test[oi]|passagg\\w+|font\\w*|appunti)\\s*[,:]?\\s*|^(?:dalle|nelle|dai|nei) (?:fonti|document\\w*|brani|passagg\\w+) (?:risulta|emerge|si legge|si ricava|e scritto|e indicato)\\s*(?:che)?\\s*[,:]?\\s*|^le fonti (?:dicono|indicano|riportano|affermano|segnalano)\\s*(?:che)?\\s*[,:]?\\s*", RegexOption.IGNORE_CASE)
    private val FILE_REF = Regex("\\b[\\p{L}\\p{N}_()\\-]+(?:\\.[\\p{L}\\p{N}_()\\-]+)*\\.(?:pdf|docx?|epub|xlsx?|txt|md|markdown|html?|csv|json)\\b", RegexOption.IGNORE_CASE)
    private val PAGE_REF = Regex("\\b(?:pagina|pag\\.|p\\.)\\s*(\\d{1,5})\\b", RegexOption.IGNORE_CASE)

    internal class Sent(
        val title: Set<String>, val itemId: String, val text: String, val folded: String, val stems: Set<String>,
        val quantities: List<Quantity>, val neg: Boolean, val words: List<String>,
    )

    /** Content stems with the quantities removed: numbers are compared by [Quantities], not by token overlap. */
    private val AUX = setOf("hai", "ho", "hanno", "abbiamo", "avete", "sei", "siamo", "siete")

    internal fun contentStems(text: String): List<String> = rawStems(text).filter { it !in AUX }

    private fun rawStems(text: String): List<String> {
        val q = Quantities.extract(text)
        if (q.isEmpty()) return SupportJudge.stems(text)
        val sb = StringBuilder(text)
        for (x in q) for (i in x.start until minOf(x.numEnd, sb.length)) sb.setCharAt(i, ' ')
        return SupportJudge.stems(sb.toString())
    }

    fun sentences(text: String): List<String> = Sentences.split(text)

    /**
     * Restatement check for a tool receipt: the model may rephrase a structured result, but every number and name it states must
     * come from that result (no word-overlap requirement, no second verification engine). Clauses stating neither are neutral.
     */
    fun verifyFigures(question: String, answer: String, receipt: EvidencePackage): ClaimReport {
        val qStems = contentStems(question).toSet()
        val have = receipt.items.flatMap { Quantities.extract(it.text) }
        val text = receipt.items.joinToString(" ") { KnowledgeText.fold(it.title + " " + it.text) }
        val claims = ArrayList<Claim>()
        for (sentence in sentences(AnswerQuality.stripPresentationNotes(answer))) for (clause in clauses(sentence)) {
            val quants = Quantities.extract(clause)
            val ents = innerCapitalised(clause).map { KnowledgeText.fold(it) }.filter { e -> SupportJudge.stems(e).firstOrNull()?.let { it !in qStems } ?: false }
            val badQ = quants.firstOrNull { q -> have.none { Quantities.compare(q, it) != Quantities.Match.DIFFERENT } }
            val badE = ents.firstOrNull { it !in text }
            claims += when {
                badQ != null -> Claim(clause, ClaimStatus.UNVERIFIED, emptyList(), "numero ${badQ.raw} non nel risultato dello strumento")
                badE != null -> Claim(clause, ClaimStatus.UNVERIFIED, emptyList(), "nome non nel risultato dello strumento: $badE")
                quants.isEmpty() && ents.isEmpty() -> Claim(clause, ClaimStatus.UNKNOWN, emptyList(), "nessun dato da controllare")
                else -> Claim(clause, ClaimStatus.EXPLICITLY_SUPPORTED, receipt.items.map { it.id }, "dati presenti nel risultato")
            }
        }
        return ClaimReport(claims)
    }

    internal fun negated(folded: String): Boolean {
        val w = words(folded)
        return w.indices.any { i -> w[i] in NEG && !(w[i] == "non" && w.getOrNull(i + 1) == "solo") }
    }

    private fun words(folded: String) = folded.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun toSent(item: EvidenceItem, text: String): Sent {
        val folded = KnowledgeText.fold(text)
        val titleStems = SupportJudge.stems(item.title + " " + item.section)
        return Sent(titleStems.toSet(), item.id, text, folded, (contentStems(text) + titleStems).toSet(), Quantities.extract(text), negated(folded), words(folded))
    }

    /** The clauses of one sentence: attribution removed, then split at ';' and at "e/ma/mentre" when a new quantity starts. */
    fun clauses(sentence: String): List<String> {
        val parts = sentence.split(Regex(";\\s+|:\\s+|\\s+[—–]\\s+|,\\s+(?:ma|mentre|però|tuttavia|però)\\s+|\\s+e\\s+(?=[^,.;]*\\d)")).map { it.trim() }.filter { it.isNotEmpty() }
        // "Non so con certezza, la capitale è Madrid": a declaration of limit ends at its comma; what follows is a proposition
        val out = ArrayList<String>()
        for (p in parts.ifEmpty { listOf(sentence) }) {
            var rest = p
            while (true) {
                val cut = rest.indexOf(", ")
                if (cut > 0 && LIMIT.containsMatchIn(KnowledgeText.fold(rest.substring(0, cut))) && cut + 2 < rest.length) {
                    out += rest.substring(0, cut).trim(); rest = rest.substring(cut + 2).trim()
                } else break
            }
            out += splitMainVerbClauses(rest)
        }
        return out.filter { it.isNotEmpty() }
    }

    private val MAIN_VERB = Regex("^(?:è|e'|sono|ha|hanno|può|possono|era|erano|viene|vengono|rappresenta|costituisce|serve|ospita|misura|pesa|dura)(?=\\s|$)", RegexOption.IGNORE_CASE)

    /** "X fa A, è anche B": split only where a NEW main clause starts; subordinates and conditions stay attached to what they qualify. */
    private fun splitMainVerbClauses(clause: String): List<String> {
        val i = clause.indexOf(", ")
        if (i <= 0) return listOf(clause)
        val right = clause.substring(i + 2)
        val left = clause.substring(0, i)
        if (!MAIN_VERB.containsMatchIn(right) || contentStems(left).size < 2 || contentStems(right).size < 2) return listOf(clause)
        return listOf(left.trim()) + splitMainVerbClauses(right.trim())
    }

    fun verify(question: String, answer: String, pkg: EvidencePackage, askRelevance: Boolean = true, receipts: List<ToolReceipt> = emptyList()): ClaimReport {
        val windows = pkg.items.flatMap { item -> Sentences.split(item.text).map { toSent(item, it) } }
        val qStems = contentStems(question).toSet()
        val known = pkg.items.joinToString(" ") { it.title + " " + it.section + " " + it.text }
        val claims = ArrayList<Claim>()
        for (sentence in sentences(AnswerQuality.stripPresentationNotes(answer))) for (clause in clauses(sentence)) {
            val action = ToolReceipts.classify(clause)
            if (action != ToolReceipts.ActionClaim.None) {
                // an action claim is checked against what the tools REPORTED, never against word overlap
                val (status, why) = ToolReceipts.check(clause, action, receipts)
                claims += Claim(clause, status, if (status == ClaimStatus.EXPLICITLY_SUPPORTED) pkg.items.filter { it.kind == EvidenceKind.TOOL }.map { it.id } else emptyList(), why)
                continue
            }
            claims += checkClaim(clause, if (askRelevance) qStems else emptySet(), windows, known)
        }
        return ClaimReport(claims)
    }

    private fun checkClaim(piece: String, qStems: Set<String>, windows: List<Sent>, evidenceText: String): Claim {
        // file names and page numbers are citations, checked by [invented]; they are not claims about quantities or words
        val text = PAGE_REF.replace(FILE_REF.replace(ATTRIBUTION.replaceFirst(piece, ""), " "), " ").replace(Regex("\\(\\s*[,;]?\\s*\\)"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?').trim()
        val folded = KnowledgeText.fold(text)
        val stems = contentStems(text).distinct()
        val quants = Quantities.extract(text)
        if (text.isEmpty() || (stems.isEmpty() && quants.isEmpty())) return Claim(piece, ClaimStatus.UNKNOWN, emptyList(), "non fattuale")
        // A declaration of limit ("non so", "non posso verificare") is legitimate ONLY while it states no checkable value or name:
        // a number or a proper name in the same clause makes it a proposition, and it is verified like any other.
        if (LIMIT.containsMatchIn(folded) && quants.isEmpty() && capitalised(text).none { e -> SupportJudge.stems(e).firstOrNull()?.let { it !in qStems } ?: false }) {
            return Claim(piece, ClaimStatus.UNKNOWN, emptyList(), "dichiarazione di limite")
        }
        invented(piece, evidenceText)?.let { return Claim(piece, ClaimStatus.UNVERIFIED, emptyList(), "riferimento inventato: $it") }
        val short = stems.size < SHORT
        val negC = negated(folded)

        fun cover(s: Sent) = if (stems.isEmpty()) 1.0 else stems.count { it in s.stems }.toDouble() / stems.size
        fun relevant(s: Sent): Boolean {
            val asked = qStems.filter { it !in s.title }
            return asked.isEmpty() || asked.count { it in s.stems }.toDouble() / asked.size >= QUESTION_RELEVANCE
        }
        // lexical overlap SELECTS the candidate sentences; the checks below decide. Several sentences can match equally well (same
        // title, different passages): the claim is supported if ANY of them passes every check, and rejected only if none does.
        // a short answer WITHOUT a number must sit in a sentence about what was asked; a number is checked by value and context instead
        val needRelevance = short && quants.isEmpty()
        val candidates = windows.filter { !needRelevance || relevant(it) }.map { it to cover(it) }
            .filter { (_, c) -> c >= COVER_SUPPORTED && (!short || c >= 1.0) }.sortedByDescending { it.second }.take(MAX_CANDIDATES)
        if (candidates.isEmpty()) {
            val near = windows.maxByOrNull { cover(it) }
            if (near != null && cover(near) >= COVER_SUPPORTED) judge(piece, negC, quants, near)?.let { return it }
            return Claim(piece, ClaimStatus.UNVERIFIED, emptyList(), if ((near?.let(::cover) ?: 0.0) < 0.4) "nessun passaggio lo conferma" else "supporto parziale")
        }
        var firstFailure: Claim? = null
        var support: Claim? = null
        var contradiction: Claim? = null
        for ((win, _) in candidates) {
            val r = checkAgainst(piece, text, folded, quants, negC, qStems, win)
            if (r.status == ClaimStatus.EXPLICITLY_SUPPORTED || r.status == ClaimStatus.DERIVED) { if (support == null) support = r }
            else {
                if (firstFailure == null) firstFailure = r
                if (r.status == ClaimStatus.CONTRADICTED && contradiction == null) contradiction = r
            }
        }
        // One passage supports it, another EQUALLY relevant one states a different value or the opposite: the sources disagree, and
        // a single citation would present one version as the fact. It is not published as fact; the policy quotes both.
        if (support != null && contradiction != null && contradiction.evidenceIds != support.evidenceIds) {
            return Claim(piece, ClaimStatus.UNVERIFIED, support.evidenceIds + contradiction.evidenceIds, "fonti in conflitto: ${contradiction.reason}")
        }
        return support ?: firstFailure!!
    }

    private fun checkAgainst(piece: String, text: String, folded: String, quants: List<Quantity>, negC: Boolean, qStems: Set<String>, best: Sent): Claim {
        judge(piece, negC, quants, best)?.let { return it }

        // names: each must occur in the sentence; two names must keep their order
        val allEntities = capitalised(text).map { KnowledgeText.fold(it) }.filter { SupportJudge.stems(it).isNotEmpty() }.distinct()
        for (e in allEntities.filter { e -> SupportJudge.stems(e).first() !in qStems }) {
            if (e !in best.folded) return Claim(piece, ClaimStatus.UNVERIFIED, listOf(best.itemId), "nome non presente nel passaggio: $e")
        }
        // order matters only between names the sentence itself contains (the article's own title is implied, not repeated)
        val present = allEntities.filter { it in best.folded }
        if (present.size >= 2) {
            val orderC = present.map { folded.indexOf(it) }
            val orderS = present.map { best.folded.indexOf(it) }
            if (orderC.zipWithNext().all { (a, b) -> a < b } != orderS.zipWithNext().all { (a, b) -> a < b }) {
                return Claim(piece, ClaimStatus.UNVERIFIED, listOf(best.itemId), "attribuzione invertita rispetto al passaggio")
            }
        }
        // qualifiers of the evidence must survive; a universal the evidence does not state must not appear
        val claimWords = words(folded).toSet()
        HEDGES.firstOrNull { it in best.words && it !in claimWords }?.let {
            if (!(it == "se" && "quando" in claimWords)) return Claim(piece, ClaimStatus.UNVERIFIED, listOf(best.itemId), "qualificatore omesso: $it")
        }
        UNIVERSALS.firstOrNull { it in claimWords && it !in best.words }?.let {
            return Claim(piece, ClaimStatus.UNVERIFIED, listOf(best.itemId), "quantificatore non nel passaggio: $it")
        }
        val converted = quants.any { q -> best.quantities.none { Quantities.compare(q, it) == Quantities.Match.EXACT } }
        return Claim(piece, if (converted) ClaimStatus.DERIVED else ClaimStatus.EXPLICITLY_SUPPORTED, listOf(best.itemId), if (converted) "conversione di unità" else "")
    }

    /** A file name or page number the evidence does not contain. */
    private fun invented(piece: String, evidenceText: String): String? {
        val ev = evidenceText.lowercase()
        for (m in FILE_REF.findAll(piece)) if (m.value.lowercase() !in ev) return m.value
        for (m in PAGE_REF.findAll(piece)) {
            val n = m.groupValues[1]
            if (!Regex("(?:pagina|pag\\.|p\\.)\\s*$n\\b|\\b$n\\b").containsMatchIn(ev)) return m.value
        }
        return null
    }

    /** CONTRADICTED / UNVERIFIED verdicts on polarity and numbers of [win], or null when they raise nothing. */
    private fun judge(text: String, negC: Boolean, quants: List<Quantity>, win: Sent): Claim? {
        if (negC != win.neg) return Claim(text, ClaimStatus.CONTRADICTED, listOf(win.itemId), "polarità opposta al passaggio")
        for (q in quants) {
            val ok = win.quantities.filter { Quantities.compare(q, it) != Quantities.Match.DIFFERENT }
            val ctxC = context(text, q.start, q.end)
            if (ok.isNotEmpty()) {
                if (win.quantities.size > 1 && misattributed(ctxC, ok, win)) {
                    return Claim(text, ClaimStatus.UNVERIFIED, listOf(win.itemId), "numero ${q.raw} attribuito a un altro elemento")
                }
                continue
            }
            val sameKind = win.quantities.filter { (q.dimension != null && it.dimension == q.dimension) || context(win.text, it.start, it.end).any { w -> w in ctxC } }
            if (sameKind.isNotEmpty()) return Claim(text, ClaimStatus.CONTRADICTED, listOf(win.itemId), "valore diverso: ${q.raw} vs ${sameKind.first().raw}")
            return Claim(text, ClaimStatus.UNVERIFIED, listOf(win.itemId), "numero ${q.raw} non nel passaggio")
        }
        return null
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

    /** Proper names not at the start of the clause (a sentence-initial capital proves nothing). */
    private fun innerCapitalised(text: String): List<String> =
        Regex("\\p{Lu}[\\p{L}\\d'’-]{2,}").findAll(text).filter { it.range.first > 0 && !text.substring(0, it.range.first).trimEnd().let { p -> p.endsWith(".") || p.endsWith("!") || p.endsWith("?") } }
            .map { it.value.substringAfter('\'').substringAfter('’') }.filter { it.length >= 3 }.toList()

    /** Words a clause may START with (capitalised only because they open the sentence): they are not names. */
    private val OPENERS = AUX + setOf("ecco", "certo", "allora", "dunque", "quindi", "infatti", "inoltre", "invece", "ovviamente", "naturalmente", "purtroppo", "comunque", "esatto", "perfetto", "bene", "ok")

    private fun capitalised(text: String): List<String> =
        Regex("\\p{Lu}[\\p{L}\\d'’-]{2,}").findAll(text)
            .filterNot { it.range.first == 0 && KnowledgeText.fold(it.value) in OPENERS }
            .map { it.value.substringAfter('\'').substringAfter('’') }.filter { it.length >= 3 }.toList()

    private fun context(text: String, start: Int, end: Int): Set<String> {
        val before = SupportJudge.stems(text.substring(0, start).takeLast(60)).takeLast(CONTEXT_WORDS)
        val after = SupportJudge.stems(text.substring(minOf(end, text.length)).take(60)).take(CONTEXT_WORDS)
        val own = SupportJudge.stems(text.substring(start, minOf(end, text.length)).filterNot { it.isDigit() })
        return (before + after + own).toSet()
    }
}
