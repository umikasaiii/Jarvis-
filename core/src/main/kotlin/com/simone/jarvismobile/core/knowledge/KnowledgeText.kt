package com.simone.jarvismobile.core.knowledge

import java.text.Normalizer

/** Language-light text helpers shared by every knowledge source (documents and ZIM archives). */
object KnowledgeText {
    data class Word(val original: String, val folded: String)

    /** Lower-case, accent-free form used for matching only (never shown). */
    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /** Folded form with every non-alphanumeric removed ("Wi-Fi" and "Wi Fi" both become "wifi"). */
    fun squash(s: String): String = fold(s).filter { it.isLetterOrDigit() }

    fun words(text: String): List<Word> =
        text.split(KnowledgeSearch.NON_WORD).filter { it.isNotEmpty() }.map { Word(it, fold(it)) }

    private val EXTRA_STOP = setOf(
        "il", "lo", "la", "le", "li", "un", "di", "da", "in", "su", "tra", "fra", "ed", "oltre", "ogni", "qual", "quanto", "quanti",
        "quante", "quanta", "dimmi", "parlami", "spiegami", "raccontami", "dammi", "cos", "perche", "chi", "fu", "era", "sono", "ha",
        "hanno", "si", "mi", "ti", "ci", "vi", "al", "alla", "allo", "alle", "ai", "dal", "dalla", "dai", "dalle", "col", "tutto",
        "tutti", "molto", "puo", "deve", "devo", "dire", "sapere", "the", "what", "is", "of", "and", "for", "about", "which",
        // request frames ("mi sai dire…", "vorrei sapere…", "cosa significa…"): they ask, they do not name what is asked about
        "sai", "saprebbe", "saresti", "potresti", "potrebbe", "vorrei", "dici", "dirmi", "indicami", "elencami", "dimmi", "significa",
        "significato", "vuol", "vuole", "spiega", "spiegare", "sapete", "conosci", "esiste", "esistono",
    )

    fun isStopword(folded: String): Boolean = folded in KnowledgeSearch.STOPWORDS || folded in EXTRA_STOP

    /** Words of the question that can name something: not function words, not interrogatives. */
    fun contentWords(text: String): List<Word> = words(text).filter { it.folded.length >= 2 && !isStopword(it.folded) }

    /** Optimal-string-alignment (Damerau-Levenshtein without repeated transposition) distance, bounded. */
    fun editDistance(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return d[a.length][b.length]
    }
}
