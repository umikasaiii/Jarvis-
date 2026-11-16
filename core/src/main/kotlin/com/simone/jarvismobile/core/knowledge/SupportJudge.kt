package com.simone.jarvismobile.core.knowledge

/**
 * KNOWLEDGE ENGINE 2.0 — decides whether ONE retrieved passage is SUFFICIENT evidence for a question, as opposed to merely
 * RELEVANT to it. Relevance ("this article's title names a word of the question") is not sufficiency: a dictionary entry
 * for "capitale" and an entry for "Portogallo" each share one word with "qual è la capitale del Portogallo?" but neither
 * states what that capital is.
 *
 * The question's content terms fall in three groups, derived from the retrieved articles themselves (no word lists):
 *  - OWN terms: named by this passage's own title/section ("Portogallo" for the article Portogallo) — implied in every window;
 *  - OTHER-ENTITY terms: named by the title of a DIFFERENT retrieved article ("capitale" when an entry "capitale" was also
 *    retrieved) — another candidate subject, not a free attribute;
 *  - ATTRIBUTE terms: named by no retrieved title ("superficie", "alto", "lingua") — what is being asked about.
 * Rules:
 *  - attributes present: a window of one or two adjacent sentences must contain them (about half when the article is about
 *    the subject, nearly all otherwise) → [Level.SUPPORTED]. If the article IS about the subject but the attribute is worded
 *    differently from the source ("alto"/"altezza"), the passage is only [Level.RELATED]: usable as a candidate, never as proof;
 *  - NO attribute but another candidate subject ("capitale del Portogallo" with both entries retrieved): the relation between
 *    them must be stated, i.e. a window must contain the other subject's term. Two lexical entries, each named by one word,
 *    are therefore NOT evidence → [Level.NONE];
 *  - every term is the passage's own subject (a definition request): the lead of the article answers.
 * A question that asks for a quantity needs a number (digits or a number word) in the window.
 *
 * Honest limits: this is lexical co-occurrence, not entailment — a window can contain the right words next to a wrong
 * statement — and there is no thesaurus: a paraphrase with different words ("città principale" for "capitale") is not
 * matched and degrades to RELATED/insufficient, never to a false SUPPORTED.
 */
object SupportJudge {
    const val MAX_WINDOW_CHARS = 420

    enum class Level { SUPPORTED, RELATED, NONE }

    /** [coverage] is the share of question terms covered by the window plus the implied own terms. */
    data class Result(
        val level: Level,
        val window: String,
        val coverage: Double,
        /** Question terms found neither in the window nor in the title/section: what the passage lacks. */
        val missing: List<String>,
        /** True when the source's own title/section names at least one question term (the article is ABOUT the subject). */
        val aboutSubject: Boolean,
        /** True when every non-implied term was found in the window (no tolerance used). */
        val complete: Boolean,
    ) {
        val supported: Boolean get() = level == Level.SUPPORTED
    }

    private val NO_RESULT = Result(Level.NONE, "", 0.0, emptyList(), false, false)

    private val NUMBER_WORDS = setOf(
        "zero", "uno", "una", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci", "undici", "dodici", "tredici",
        "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove", "venti", "trenta", "quaranta", "cinquanta",
        "sessanta", "settanta", "ottanta", "novanta", "cento", "mille", "milione", "milioni", "miliardo", "miliardi",
    )

    /**
     * Content terms of [text], stemmed. One pass over the folded text, no regex and no intermediate lists: this runs once per
     * indexed passage and once per question. Equivalent to splitting on non-letter/digit runs after joining "Wi-Fi"-style
     * hyphens, dropping stop words and 1–2-letter tokens (a 2-character token is kept only when it holds a digit: "K2").
     */
    fun stems(text: String): List<String> {
        val s = KnowledgeText.fold(text)
        val out = ArrayList<String>(s.length / 7 + 1)
        val tok = StringBuilder()
        var hasDigit = false
        var i = 0
        val n = s.length
        fun flush() {
            if (tok.isNotEmpty()) {
                val w = tok.toString()
                if ((w.length >= 3 || (w.length == 2 && hasDigit)) && !KnowledgeText.isStopword(w)) out += KnowledgeSearch.stemWord(w)
                tok.setLength(0); hasDigit = false
            }
        }
        while (i < n) {
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            val letter = Character.isLetter(cp)
            if (letter || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
                tok.appendCodePoint(cp); if (!letter) hasDigit = true
            } else if ((cp == '-'.code || cp == 0x2010 || cp == 0x2011) && tok.isNotEmpty() && i + len < n &&
                Character.isLetter(s[i - 1]) && Character.isLetter(s.codePointAt(i + len))) {
                // "Wi-Fi" and "wifi" are one word
            } else flush()
            i += len
        }
        flush()
        return out
    }

    /** Grammar of the question, not its subject: "quanti/quante/quanto…", "in che anno", "a che anno". */
    fun asksQuantity(question: String): Boolean {
        val q = KnowledgeText.fold(question)
        return Regex("""\b(quant[ioae]|in che anno|che anno|a che anno|in quale anno)\b""").containsMatchIn(q)
    }

    fun sentences(text: String): List<String> =
        text.replace('\n', ' ').split(Regex("""(?<=[.!?])\s+(?=[\p{Lu}\d«"'(])""")).map { it.trim() }.filter { it.isNotEmpty() }

    /** A numeric token standing alone: "27", "2.140", "3,2" — NOT the digits inside a name or code ("Frazione000", "K2", "A4"). */
    private val NUM = Regex("""(?<![\p{L}\p{N}])\d[\d.,]*(?![\p{L}\p{N}])""")
    private val UNIT_AFTER = Regex("""^\s*(m\b|km|cm|mm|kg|g\b|%|per\s*cento|metri|metro|chilometri|centimetri|gradi|abitanti|persone|euro|ettari|litri|tonnellate|miglia|kmq|mq)""")
    private val YEAR_BEFORE = Regex("""(?:\bnel|\bnell|\banno|\bdal|\bdall|\bnei|\bfino al)\W*$""")
    private val NEG_BEFORE = Regex("""\bnon\b[^.;]{0,24}$""")

    /**
     * An epistemic denial: the passage says the value is NOT known / documented / determinable ("non si conosce l'anno", "data sconosciuta",
     * "non è noto"). It names the property but states no value, so it can never be the proof of one. Deliberately a small closed family of
     * grammatical cues (negation + knowing/documenting verb or adjective, "sconosciuto/ignoto/incerto"), not a list of sentences.
     */
    private val WITHHOLDS = Regex(
        """\bnon\s+(?:si\s+|ne\s+)?(?:conosc\w*|sa\b|sap\w*|e\s+(?:ancora\s+|mai\s+)?(?:not[oaie]|chiar\w*|cert\w*|documentat\w*|accertat\w*|indicat\w*|specificat\w*|riportat\w*|determinabil\w*)|sono\s+(?:ancora\s+)?(?:not\w*|documentat\w*|accertat\w*)|e\s+stat\w+\s+(?:possibile|accertat\w*|documentat\w*|determinat\w*)|puo\s+(?:stabilire|determinare|sapere)|si\s+puo\s+\w+)|\b(?:sconosciut\w*|ignot\w*|incert\w*|imprecisat\w*|indeterminat\w*|rimane\s+un\s+mistero)\b""")

    fun withholdsValue(sentence: String): Boolean = WITHHOLDS.containsMatchIn(KnowledgeText.fold(sentence))

    /**
     * Does [sentence] state a number that can answer the question? A standalone numeric token only (never digits inside an identifier), not one the
     * sentence itself negates ("non nel 1500"), and of the right kind: a YEAR question needs a year-sized number that is not a measure ("82 metri");
     * any other quantity question ignores a number that is introduced as a year ("nel 1347").
     */
    private fun statesNumber(sentence: String, wantsYear: Boolean): Boolean {
        val f = KnowledgeText.fold(sentence)
        for (m in NUM.findAll(f)) {
            val before = f.substring(maxOf(0, m.range.first - 28), m.range.first)
            if (NEG_BEFORE.containsMatchIn(before)) continue
            val digits = m.value.trimEnd('.', ',')
            val after = f.substring(m.range.last + 1).take(14)
            val measure = UNIT_AFTER.containsMatchIn(after)
            if (wantsYear) { if (digits.length in 3..4 && digits.all { it.isDigit() } && !measure) return true }
            else if (!(digits.length in 3..4 && YEAR_BEFORE.containsMatchIn(before))) return true
        }
        return KnowledgeSearch.NON_WORD.split(f).any { isNumberWord(it) } && !wantsYear
    }

    /** "ottantadue", "centoventi", "milleduecento": a number word, possibly a compound of the simple ones (tens/hundreds + units). */
    private val COMPOUND_NUMBER = Regex("""^(?:(?:due|tre|quattro|cinque|sei|sette|otto|nove)?cento)?(?:(?:venti|trenta|quaranta|cinquanta|sessanta|settanta|ottanta|novanta)(?:uno|due|tre|quattro|cinque|sei|sette|otto|nove)?|dici\w*|undici|dodici|tredici)?(?:(?:due|tre|quattro|cinque|sei|sette|otto|nove)?mil[al]?\w*)?$""")
    private fun isNumberWord(w: String): Boolean = w in NUMBER_WORDS || (w.length >= 6 && w.any { it in "cdqstv" } && COMPOUND_NUMBER.matches(w) && NUMBER_WORDS.any { w.contains(it) })

    /**
     * Light derivational root of the quality-noun family "alto → altezza", "lungo → lunghezza", "largo → larghezza": the adjective stem and the stem of
     * the noun in -ezza reduce to the same root. Only this ONE suffix family is related (it is regular and the pair keeps the same relation: the
     * measure of the property); "-ità" is deliberately not (università/universo, qualità/quale) and needs no rule where the stemmer already agrees.
     */
    fun dimensionRoot(stem: String): String {
        var r = stem.removeSuffix("ezz")
        if (r.endsWith("gh")) r = r.dropLast(1)
        if (r.length >= 4 && r.last() in "oaie") r = r.dropLast(1)
        return r
    }

    private fun derived(q: String, pool: Set<String>): Boolean {
        if (q.length < 3 || (!q.endsWith("ezz") && pool.none { it.endsWith("ezz") })) return false
        val rq = dimensionRoot(q)
        if (rq.length < 3) return false
        return pool.any { d -> d != q && (d.endsWith("ezz") || q.endsWith("ezz")) && dimensionRoot(d) == rq }
    }

    /** Exact stem, or — for longer terms only — one edit away (typo / inflection). [minLen] is the shortest word allowed to be fuzzy. */
    private fun matches(q: String, pool: Set<String>, minLen: Int, derive: Boolean = false): Boolean {
        if (q in pool) return true
        if (derive && derived(q, pool)) return true
        if (q.length < minLen) return false
        return pool.any { d -> d.length >= minLen && kotlin.math.abs(d.length - q.length) <= 1 && KnowledgeText.editDistance(q, d, 1) <= 1 }
    }

    /** The question terms a title/section names. Typos are tolerated from 5 letters: the article was already chosen by its title. */
    fun ownTerms(qStems: List<String>, title: String, section: String = ""): Set<String> {
        val implied = (stems(title) + stems(section)).toSet()
        return qStems.filter { matches(it, implied, 4) }.toSet()
    }

    /**
     * Judges [chunk] against the question's content [qStems]. [isLead] marks the first passage of an article;
     * [otherEntityTerms] are the question terms named by the titles of the OTHER retrieved articles.
     */
    fun judge(
        qStems: List<String>, quantity: Boolean, chunk: KnowledgeChunk, isLead: Boolean,
        otherEntityTerms: Set<String> = emptySet(),
        /** Question terms already known to be named by this source (the title phrase the search matched, redirect names included). */
        namedTerms: Set<String> = emptySet(),
        /** The question asks for a year ("in che anno…"): then only a year-sized number that is not a measure is a value. */
        wantsYear: Boolean = false,
    ): Result {
        if (qStems.isEmpty()) return NO_RESULT
        val terms = qStems.distinct()
        val own = ownTerms(terms, chunk.sourceTitle, chunk.section) + namedTerms.filter { it in terms }
        val aboutSubject = own.isNotEmpty()
        val rest = terms - own
        val others = rest.filter { it in otherEntityTerms }
        val attrs = rest - others.toSet()

        val sents = sentences(chunk.text)
        if (sents.isEmpty()) return NO_RESULT.copy(aboutSubject = aboutSubject, missing = rest)

        if (rest.isEmpty()) {
            // every term is the article's own subject: a definition/overview question. The lead is the answer-bearing text.
            if (!isLead) return NO_RESULT.copy(aboutSubject = true)
            return Result(Level.SUPPORTED, sents.take(2).joinToString(" ").take(MAX_WINDOW_CHARS), 1.0, emptyList(), true, true)
        }

        // What a window must contain. A DICTIONARY entry describes a word: any other term of the question must be stated in the
        // same window ("capitale" + "Portogallo"), never merely half of them. An encyclopedia article about the subject needs
        // only about half of the remaining terms (the question's verbs — "si parla", "si trova" — are often worded differently
        // from the source); one that names none of the terms must contain them (nearly) all together.
        // The terms that are other articles' subjects are not attributes this article must state — except when there is no
        // attribute at all ("capitale del Portogallo" with both entries retrieved): then the relation itself must be stated.
        // An article that is not ABOUT any asked term must contain every term, whatever other articles were retrieved.
        val goal = if (aboutSubject && attrs.isNotEmpty()) attrs else rest
        val needed = when {
            chunk.lexical -> goal.size
            aboutSubject -> kotlin.math.ceil(0.5 * goal.size).toInt().coerceAtLeast(1)
            goal.size <= 2 -> goal.size
            goal.size <= 4 -> goal.size - 1 // one term may be a category word ("quale ORGANO produce l'insulina?")
            else -> kotlin.math.ceil(0.75 * goal.size).toInt()
        }

        val stemSets = sents.map { stems(it).toSet() }
        // a sentence that denies knowing the value is no proof of it; a number is judged per sentence (never from digits inside a name)
        val withheld = sents.map { withholdsValue(it) }
        val numeric = sents.map { !withholdsValue(it) && statesNumber(it, wantsYear) }
        class Win(val text: String, val covered: Set<String>, val number: Boolean, val missing: List<String>, val coverage: Double)
        val wins = ArrayList<Win>()
        var bestNumber: String? = null
        for (i in sents.indices) for (len in 1..2) {
            val end = i + len
            if (end > sents.size) continue
            val text = sents.subList(i, end).joinToString(" ")
            if (text.length > MAX_WINDOW_CHARS * 2) continue
            if ((i until end).any { withheld[it] }) continue
            val pool = HashSet<String>().also { s -> for (k in i until end) s += stemSets[k] }
            val number = (i until end).any { numeric[it] }
            if (number && bestNumber == null) bestNumber = text.take(MAX_WINDOW_CHARS)
            val coveredRest = rest.filter { matches(it, pool, 6) }
            wins += Win(text, goal.filter { matches(it, pool, 6) }.toSet(), number, rest - coveredRest.toSet(), (own.size + coveredRest.size).toDouble() / terms.size)
        }
        // best single window: most goal terms, then most question terms, then shortest
        val ranked = wins.sortedWith(compareByDescending<Win> { it.covered.size }.thenByDescending { it.coverage }.thenBy { it.text.length })
        val top = ranked.firstOrNull()
        var best: Result? = null
        if (top != null) {
            // a question with several attributes ("capitale e lingua") is answered by up to two windows that cover DIFFERENT terms
            val chosen = arrayListOf(top)
            var covered = top.covered
            for (w in ranked.drop(1)) {
                if (chosen.size >= 2 || covered.size >= goal.size) break
                if ((w.covered - covered).isNotEmpty() && w.text != top.text && !top.text.contains(w.text) && !w.text.contains(top.text)) { chosen += w; covered = covered + w.covered }
            }
            val number = chosen.any { it.number }
            val ok = covered.size >= needed && (!quantity || number)
            val joined = chosen.joinToString(" … ") { it.text }.take(MAX_WINDOW_CHARS * 2)
            val missing = rest - rest.filter { r -> chosen.any { w -> r !in w.missing } }.toSet()
            best = Result(if (ok) Level.SUPPORTED else Level.NONE, joined, chosen.maxOf { it.coverage }, missing, aboutSubject, missing.isEmpty())
        }
        val b = best ?: return NO_RESULT.copy(aboutSubject = aboutSubject, missing = rest)
        if (b.supported) return b
        // A quality noun of the same family as the asked adjective ("alto" → "altezza") next to a number, in an article about the subject: a CANDIDATE
        // (RELATED), never proof — the property is worded differently and no thesaurus/NLI decides they are the same relation (documented product rule).
        if (aboutSubject && attrs.isNotEmpty() && quantity && !chunk.lexical) {
            val i = sents.indices.firstOrNull { numeric[it] && !withheld[it] && attrs.any { a -> derived(a, stemSets[it]) } }
            if (i != null) return b.copy(level = Level.RELATED, window = sents[i].take(MAX_WINDOW_CHARS))
        }
        // The article is ABOUT the subject but the asked attribute is worded differently from the source: a candidate, not proof.
        if (aboutSubject && attrs.isNotEmpty() && isLead && !chunk.lexical) {
            val w = (if (quantity) bestNumber else null) ?: sents.take(2).joinToString(" ").take(MAX_WINDOW_CHARS)
            return b.copy(level = Level.RELATED, window = w)
        }
        return b
    }
}
