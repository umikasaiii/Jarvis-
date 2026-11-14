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

    fun stems(text: String): List<String> =
        KnowledgeSearch.NON_WORD.split(joinHyphens(KnowledgeText.fold(text)))
            .filter { (it.length >= 3 || (it.length == 2 && it.any(Char::isDigit))) && !KnowledgeText.isStopword(it) } // "K2", "A4"
            .map { KnowledgeSearch.stemWord(it) }

    /** "Wi-Fi" and "wifi" are one word ("wi", "fi" alone are too short to carry any meaning). */
    private fun joinHyphens(s: String): String = s.replace(Regex("""(?<=\p{L})[-\u2010\u2011](?=\p{L})"""), "")

    /** Grammar of the question, not its subject: "quanti/quante/quanto…", "in che anno", "a che anno". */
    fun asksQuantity(question: String): Boolean {
        val q = KnowledgeText.fold(question)
        return Regex("""\b(quant[ioae]|in che anno|che anno|a che anno|in quale anno)\b""").containsMatchIn(q)
    }

    fun sentences(text: String): List<String> =
        text.replace('\n', ' ').split(Regex("""(?<=[.!?])\s+(?=[\p{Lu}\d«"'(])""")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun hasNumber(text: String): Boolean =
        text.any { it.isDigit() } || KnowledgeSearch.NON_WORD.split(KnowledgeText.fold(text)).any { it in NUMBER_WORDS }

    /** Exact stem, or — for longer terms only — one edit away (typo / inflection). [minLen] is the shortest word allowed to be fuzzy. */
    private fun matches(q: String, pool: Set<String>, minLen: Int): Boolean {
        if (q in pool) return true
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
        class Win(val text: String, val covered: Set<String>, val number: Boolean, val missing: List<String>, val coverage: Double)
        val wins = ArrayList<Win>()
        var bestNumber: String? = null
        for (i in sents.indices) for (len in 1..2) {
            val end = i + len
            if (end > sents.size) continue
            val text = sents.subList(i, end).joinToString(" ")
            if (text.length > MAX_WINDOW_CHARS * 2) continue
            val pool = HashSet<String>().also { s -> for (k in i until end) s += stemSets[k] }
            val number = hasNumber(text)
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
        // The article is ABOUT the subject but the asked attribute is worded differently from the source: a candidate, not proof.
        if (aboutSubject && attrs.isNotEmpty() && isLead && !chunk.lexical) {
            val w = (if (quantity) bestNumber else null) ?: sents.take(2).joinToString(" ").take(MAX_WINDOW_CHARS)
            return b.copy(level = Level.RELATED, window = w)
        }
        return b
    }
}
