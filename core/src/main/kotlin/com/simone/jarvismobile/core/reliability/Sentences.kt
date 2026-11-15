package com.simone.jarvismobile.core.reliability

/** Sentence boundaries shared by the evidence cutter and the claim verifier: a decimal point or an abbreviation is not an end. */
object Sentences {
    /** End offsets (exclusive) of every sentence of [text]. */
    fun ends(text: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n') { out += i + 1; i++; continue }
            if (c == '.' || c == '!' || c == '?') {
                var j = i + 1
                while (j < n && (text[j] == '.' || text[j] == '"' || text[j] == '»' || text[j] == ')' || text[j] == '”')) j++
                val atEnd = j >= n
                if (atEnd || text[j].isWhitespace()) {
                    var k = j
                    while (k < n && text[k] == ' ') k++
                    val next = if (k < n) text[k] else ' '
                    val decimal = c == '.' && i > 0 && text[i - 1].isDigit() && k < n && next.isDigit() && k == j
                    val abbreviation = c == '.' && k < n && (next.isLowerCase() || (next.isDigit() && ABBR.contains(wordBefore(text, i))))
                    if (atEnd || (!decimal && !abbreviation)) { out += j; i = j; continue }
                }
            }
            i++
        }
        if (out.lastOrNull() != n) out += n
        return out
    }

    private val ABBR = setOf("pag", "pp", "p", "n", "nr", "art", "cap", "fig", "es", "ecc", "sez", "vol", "par", "tab", "cfr", "sig", "dott", "prof", "ing", "avv", "sr", "tel", "ca")

    private fun wordBefore(text: String, dot: Int): String {
        var j = dot
        while (j > 0 && text[j - 1].isLetter()) j--
        return text.substring(j, dot).lowercase()
    }

    fun split(text: String): List<String> {
        val res = ArrayList<String>()
        var start = 0
        for (e in ends(text)) {
            val piece = text.substring(start, e).trim().trimStart('-', '•', '*', '·', ' ').trim()
            if (piece.isNotEmpty()) res += piece
            start = e
        }
        return res
    }

    /** Spans (start, end) of the sentences of [text]; blank ones are skipped. */
    fun spans(text: String): List<IntRange> {
        val res = ArrayList<IntRange>()
        var start = 0
        for (e in ends(text)) {
            if (text.substring(start, e).isNotBlank()) res += start until e
            start = e
        }
        return res
    }
}
