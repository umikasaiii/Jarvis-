package com.simone.jarvismobile.core.quality

import com.simone.jarvismobile.core.llm.DegenerateOutput

/**
 * HOTFIX 5 — the ONE post-generation stage (after cleaning, before the commit): conservative, language-shape
 * checks and an honest presentation of where a statement comes from. It contains NO knowledge and NO question
 * routing: it never decides what the right answer is, it only (a) notices an output that cannot be a valid answer
 * and (b) states provenance. It is NOT an independent verifier: a second pass of the same model would not be.
 */
object AnswerQuality {

    enum class Flag {
        EMPTY,
        /** Ends mid-sentence (dangling function word / separator / open bracket): likely cut off. */
        INCOMPLETE,
        DEGENERATE,
        /** Near-copy of an answer already given to a DIFFERENT question: a reply from an earlier turn. */
        ECHO_OF_EARLIER_ANSWER,
        /** Number(s) in a source-grounded answer that appear in none of the retrieved passages. */
        UNSUPPORTED_NUMBERS,
    }

    enum class Provenance {
        /** Result of a deterministic tool the system ran: authoritative, no caveat. */
        TOOL,
        /** Built from retrieved offline passages. */
        SOURCE,
        /** The model's own memory, unverified. */
        MODEL_GENERAL,
    }

    data class Assessment(val flags: Set<Flag>, val numericClaims: List<String>, val unsupportedNumbers: List<String>) {
        /** Worth ONE bounded retry on a fresh conversation (the slot already handles DEGENERATE itself). */
        val retryable: Boolean get() = Flag.EMPTY in flags || Flag.INCOMPLETE in flags || Flag.ECHO_OF_EARLIER_ANSWER in flags
        val ok: Boolean get() = flags.isEmpty()
    }

    fun evaluate(
        question: String,
        answer: String,
        earlierAnswers: List<String>,
        earlierQuestions: List<String> = emptyList(),
        evidence: String? = null,
    ): Assessment {
        val flags = LinkedHashSet<Flag>()
        val a = answer.trim()
        if (a.isEmpty()) {
            flags += Flag.EMPTY
            return Assessment(flags, emptyList(), emptyList())
        }
        if (DegenerateOutput.analyze(a).degenerate) flags += Flag.DEGENERATE
        if (looksIncomplete(a)) flags += Flag.INCOMPLETE
        if (echoesEarlierAnswer(question, a, earlierAnswers, earlierQuestions)) flags += Flag.ECHO_OF_EARLIER_ANSWER

        val claims = numericClaims(a).filterNot { digitsOf(it) in digitSet(question) }
        // With sources in hand the bar is higher than for memory: ANY multi-digit/decimal number the answer states must
        // appear in the retrieved passages (or have been typed by the user), not only "claim-shaped" ones.
        val unsupported = if (evidence != null) {
            val ev = digitSet(evidence)
            val asked = digitSet(question)
            significantNumbers(a).filter { digitsOf(it) !in ev && digitsOf(it) !in asked }
        } else {
            emptyList()
        }
        if (unsupported.isNotEmpty()) flags += Flag.UNSUPPORTED_NUMBERS
        return Assessment(flags, claims, unsupported)
    }

    /**
     * Provenance note appended to the SPOKEN/written answer, only when it adds information:
     *  - model-memory answer containing quantitative claims  -> "dato non verificato offline"
     *  - source-grounded answer with numbers absent from the sources -> they are not in the sources
     * Tool results and answers without numeric claims are returned untouched.
     */
    fun present(answer: String, provenance: Provenance, assessment: Assessment): String {
        val a = answer.trimEnd()
        return when {
            provenance == Provenance.TOOL -> a
            provenance == Provenance.MODEL_GENERAL && assessment.numericClaims.isNotEmpty() -> a + "\n" + NOTE_UNVERIFIED
            provenance == Provenance.SOURCE && assessment.unsupportedNumbers.isNotEmpty() -> a + "\n" + NOTE_NOT_IN_SOURCES
            else -> a
        }
    }

    /** The note lines are presentation only: they must never be replayed to the model as its own speech. */
    fun stripPresentationNotes(text: String): String =
        text.lineSequence().filterNot { it.trim() == NOTE_UNVERIFIED || it.trim() == NOTE_NOT_IN_SOURCES }
            .joinToString("\n").trimEnd()

    const val NOTE_UNVERIFIED = "Nota: i numeri vengono dalla memoria del modello, non da una fonte verificata offline."
    const val NOTE_NOT_IN_SOURCES = "Nota: alcuni valori non compaiono nelle fonti consultate."

    // ---- completeness ---------------------------------------------------------------------------------

    /** Italian function words that cannot end a sentence. */
    private val DANGLING = setOf(
        "e", "ed", "o", "ma", "se", "che", "di", "a", "da", "in", "con", "su", "per", "tra", "fra",
        "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "del", "dello", "della", "dei", "degli", "delle",
        "al", "allo", "alla", "ai", "agli", "alle", "dal", "dalla", "nel", "nella", "nei", "sul", "sulla",
        "come", "quando", "dove", "anche", "non", "più", "cui", "perché", "mentre", "oppure", "quindi", "poi", "sia", "né",
    )

    fun looksIncomplete(answer: String): Boolean {
        val t = answer.trim()
        if (t.length < 12) return false // very short answers ("Roma") are legitimately unpunctuated
        val last = t.last()
        if (last in ".!?…»\")]}'" || last.isDigit() || last == '%') return !balancedOpen(t)
        if (last in ",;:-–(" ) return true
        if (!balancedOpen(t)) return true
        val lastWord = t.split(Regex("""[\s]+""")).last().trim(',', ';', ':').lowercase()
        return lastWord in DANGLING
    }

    /** True when every bracket/quote opened is closed. */
    private fun balancedOpen(t: String): Boolean {
        var paren = 0
        var guillemet = 0
        for (c in t) {
            when (c) { '(' -> paren++; ')' -> paren--; '«' -> guillemet++; '»' -> guillemet-- }
        }
        val dq = t.count { it == '"' }
        return paren <= 0 && guillemet <= 0 && dq % 2 == 0
    }

    // ---- echo of an earlier answer ----------------------------------------------------------------------

    private fun echoesEarlierAnswer(
        question: String,
        answer: String,
        earlierAnswers: List<String>,
        earlierQuestions: List<String>,
    ): Boolean {
        val words = wordSet(answer)
        if (words.size < 6) return false
        val qWords = wordSet(question)
        for ((i, prev) in earlierAnswers.withIndex()) {
            val pw = wordSet(prev)
            if (pw.size < 6) continue
            if (jaccard(words, pw) < ECHO_SIMILARITY) continue
            // The same answer is legitimate when the user asked the same thing again.
            val sameAsk = earlierQuestions.getOrNull(i)?.let { jaccard(qWords, wordSet(it)) >= SAME_QUESTION } ?: false
            if (!sameAsk) return true
        }
        return false
    }

    private const val ECHO_SIMILARITY = 0.8
    private const val SAME_QUESTION = 0.6

    // ---- numeric claims ---------------------------------------------------------------------------------

    private val NUMBER = Regex("""\d[\d.,]*\d|\d""")
    private val SCALE = setOf("%", "€", "$", "milioni", "milione", "miliardi", "miliardo", "mila", "mille", "km", "kg", "m", "cm", "mm", "mq", "km²", "m²", "gb", "tb", "mb", "ghz", "mhz", "anni", "secoli")

    /** Numbers that read as a factual claim: ≥3 digits, a decimal part, or a magnitude/unit right after. */
    fun numericClaims(text: String): List<String> {
        val out = ArrayList<String>()
        for (m in NUMBER.findAll(text)) {
            val tok = m.value.trimEnd('.', ',')
            val digits = digitsOf(tok)
            val hasDecimal = tok.contains(Regex("""[.,]\d{1,2}$""")) && tok.any { it == ',' || it == '.' } && digits.length > 1
            val after = text.substring(m.range.last + 1).trimStart().takeWhile { !it.isWhitespace() }.lowercase().trimEnd('.', ',', ';', ':', ')')
            val unit = after in SCALE || text.getOrNull(m.range.last + 1) == '%'
            if (digits.length >= 3 || hasDecimal || unit) out += tok
        }
        return out.distinct()
    }

    /** Every number with at least two digits or a decimal part (list markers like "1." are ignored). */
    fun significantNumbers(text: String): List<String> =
        NUMBER.findAll(text).map { it.value.trimEnd('.', ',') }.filter { digitsOf(it).length >= 2 }.distinct().toList()

    private fun digitsOf(tok: String) = tok.filter { it.isDigit() }

    private fun digitSet(text: String): Set<String> = NUMBER.findAll(text).map { digitsOf(it.value) }.toSet()

    // ---- text similarity --------------------------------------------------------------------------------

    private fun wordSet(s: String): Set<String> =
        s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 2 }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }
}
