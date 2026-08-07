package com.simone.jarvismobile.core.agenda

/**
 * Recognises that something the user just *said* implies an agenda item is done.
 *
 * "Sono andato dal dentista" is not a command — it is a statement. But if the
 * dentist is sitting in the agenda as an open item, the useful thing is to
 * notice and offer to tick it off, instead of leaving a stale reminder to fire
 * later for something that already happened.
 *
 * It only ever *proposes*: nothing is completed without the user confirming,
 * because misreading a sentence must not silently rewrite the calendar.
 */
object CompletionHint {

    /**
     * The open entry that [utterance] most likely completes, or null.
     *
     * Deliberately conservative: it needs a past-tense marker AND a solid word
     * overlap with exactly one open entry. Two candidates mean ambiguity, and
     * ambiguity resolves to "ask nothing" rather than to a guess.
     */
    fun detect(utterance: String, open: List<AgendaEntry>): AgendaEntry? {
        if (open.isEmpty()) return null
        val text = normalize(utterance)
        if (!PAST_MARKER.containsMatchIn(text)) return null
        // A question is asking, not reporting: "sono andato dal dentista?" is not
        // a claim that it happened.
        if (utterance.trimEnd().endsWith('?')) return null

        val said = contentWords(text)
        if (said.isEmpty()) return null

        val scored = open.filter { !it.done }.mapNotNull { entry ->
            val words = contentWords(normalize(entry.text))
            if (words.isEmpty()) return@mapNotNull null
            val hits = words.count { it in said }
            // Two shared words, or a one-word entry matched whole. A single word
            // shared with a longer entry is not evidence: "ho chiamato Marco"
            // overlaps "chiamare Marco" and "chiamare Marco Rossi" equally on the
            // name alone, and picking either would be a coin toss.
            val strong = hits >= 2 || (words.size == 1 && hits == 1)
            if (!strong) return@mapNotNull null
            entry to hits.toDouble() / words.size
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) return null
        // Still ambiguous: two entries match about equally well. Say nothing.
        if (scored.size > 1 && scored[0].second - scored[1].second < CLEAR_MARGIN) return null
        return scored.first().first
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')

    private fun contentWords(s: String): Set<String> = s
        .split(NON_WORD)
        .filter { it.length >= 4 && it !in STOPWORDS }
        .toSet()

    /** How much better the best match must be than the runner-up. */
    private const val CLEAR_MARGIN = 0.15

    private val NON_WORD = Regex("""[^\p{L}\p{Nd}]+""")

    /**
     * Past-tense/completion markers. Unicode-aware boundaries, since Java's `\b`
     * fails next to accents (ADR 0004).
     */
    private val PAST_MARKER = Regex(
        """(?<![\p{L}\p{Nd}])(?:""" +
            """sono\s+(?:andato|andata|stato|stata|passato|passata)|""" +
            """ho\s+(?:fatto|finito|completato|terminato|portato|preso|comprato|chiamato|risolto)|""" +
            """abbiamo\s+(?:fatto|finito|completato)|""" +
            """[eè]\s+(?:fatto|fatta|finito|finita)|""" +
            """fatto|finito|completato|risolto|sistemato""" +
            """)(?![\p{L}\p{Nd}])""",
    )

    private val STOPWORDS = setOf(
        "sono", "andato", "andata", "stato", "stata", "fatto", "fatta", "finito",
        "finita", "completato", "terminato", "oggi", "ieri", "domani", "dalla",
        "dallo", "della", "delle", "degli", "questo", "questa", "quello", "quella",
        "anche", "gia", "adesso", "appena", "poi", "mio", "mia", "miei",
    )
}
