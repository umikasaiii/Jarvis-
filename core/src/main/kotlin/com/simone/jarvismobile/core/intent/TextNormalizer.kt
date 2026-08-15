package com.simone.jarvismobile.core.intent

/**
 * Turns what the user actually said into a stable form the parsers can match.
 *
 * Speech-to-text output varies in ways that carry no meaning: accents, capitals,
 * punctuation, polite wrappers ("puoi …, per favore"), fillers ("insomma"), and
 * the assistant's own name at the front. Normalising those away once means every
 * parser can use one plain pattern instead of a fragile list of variants.
 *
 * It deliberately does NOT stem or drop articles globally: "la nota" vs "le
 * note" is a meaningful difference elsewhere, and removing articles blindly
 * corrupts titles like "il Signore degli Anelli".
 */
object TextNormalizer {

    /** Lowercases and folds Italian accents; keeps word order and content. */
    fun fold(s: String): String = s.lowercase()
        .replace('à', 'a').replace('á', 'a')
        .replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('í', 'i')
        .replace('ò', 'o').replace('ó', 'o')
        .replace('ù', 'u').replace('ú', 'u')
        .replace('’', '\'')

    /**
     * Full normalisation: folded, stripped of wake name, polite wrappers,
     * fillers and edge punctuation, with runs of whitespace collapsed.
     */
    fun normalize(s: String): String {
        var t = fold(s).trim()
        t = t.trim('.', '!', '?', ',', ';', ' ', '"', '«', '»')

        var changed = true
        while (changed) {
            changed = false
            for (p in LEADING_WRAPPERS) {
                val m = p.find(t) ?: continue
                if (m.range.first == 0 && m.range.last >= 0) {
                    t = t.removeRange(m.range).trim()
                    changed = true
                }
            }
        }
        for (f in FILLERS) t = f.replace(t, " ")
        return t.replace(WHITESPACE, " ").trim().trim(',', ' ')
    }

    /** Normalisation plus article removal — for comparing two titles loosely. */
    fun key(s: String): String = normalize(s)
        .replace(ARTICLES, " ")
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * True when [needle] plausibly names [candidate]. Tolerant on purpose: word
     * order may differ and articles are ignored, but every word of the needle
     * must appear, so "dentista" matches "visita dal dentista" while "dentista
     * lunedì" does not match a Thursday entry's title.
     */
    fun matches(needle: String, candidate: String): Boolean {
        val n = key(needle)
        val c = key(candidate)
        if (n.isBlank()) return false
        if (c.contains(n)) return true
        val words = n.split(' ').filter { it.length >= 3 }
        return words.isNotEmpty() && words.all { c.contains(it) }
    }

    private val WHITESPACE = Regex("""\s+""")

    /** Articles and bare prepositions, removed only for loose title comparison. */
    private val ARTICLES = Regex(
        """(?<!\p{L})(?:il|lo|la|i|gli|le|un|uno|una|l'|dell'|del|della|dei|degli|delle|""" +
            """al|allo|alla|ai|agli|alle|dal|dalla|di|da|a|in|con|su|per)(?!\p{L})""",
    )

    /**
     * Polite/vocative openers. Anchored to the start and applied repeatedly, so
     * "Ehi JARVIS, per favore potresti …" unwraps completely.
     */
    private val LEADING_WRAPPERS = listOf(
        Regex("""^(?:ehi|hey|ok|okay|senti|scusa|ciao)\s+"""),
        Regex("""^jarvis[ ,]+"""),
        Regex("""^(?:per\s+favore|per\s+piacere|gentilmente)[ ,]+"""),
        Regex("""^(?:puoi|potresti|riesci\s+a|sai|vorrei\s+che|voglio\s+che|ti\s+va\s+di|mi\s+puoi|""" +
            """mi\s+potresti|per\s+cortesia)\s+"""),
        Regex("""^(?:dai|allora|quindi)\s+"""),
    )

    /**
     * Filler words that never change meaning. Matched with word boundaries so
     * they can sit anywhere in the sentence, and "per favore" is included here
     * too because it is usually trailing rather than leading.
     */
    private val FILLERS = listOf(
        Regex("""(?<!\p{L})per\s+favore(?!\p{L})"""),
        Regex("""(?<!\p{L})per\s+piacere(?!\p{L})"""),
        // NB: "appunto" is deliberately NOT a filler here. It also means "note",
        // and dropping it turned "elimina l'appunto sulla moto" into a sentence
        // with no object at all.
        Regex("""(?<!\p{L})(?:insomma|praticamente|diciamo|cioe|ecco|comunque)(?!\p{L})"""),
        Regex("""(?<!\p{L})un\s+attimo(?!\p{L})"""),
    )
}
