package com.simone.jarvismobile.core.speech

/**
 * Turns a written reply into something worth listening to.
 *
 * Two jobs, both of which a rate/pitch slider cannot do:
 *
 *  1. **Clean.** A model writes for the eye — Markdown asterisks, bullet
 *     dashes, "es.", "ecc.", emoji, bare 24h times. Read literally, all of that
 *     comes out as noise ("asterisco asterisco"), so it is normalised first.
 *  2. **Breathe.** Speech sounds human when it pauses where a person would: a
 *     beat after a full stop, a shorter one at a comma, a longer one between
 *     list items. Android's engine gives almost none of this, so the text is cut
 *     into segments with explicit silences between them.
 *
 * Pure Kotlin so the rhythm is unit-tested rather than judged by ear on a phone.
 */
object SpeechShaper {

    /** Base pauses in ms at expressiveness 1.0, before [SpeechStyle.pauseScale]. */
    private const val SENTENCE_PAUSE = 320L
    private const val CLAUSE_PAUSE = 150L
    private const val LIST_PAUSE = 260L

    /** Segments longer than this get an extra breath, so long answers don't rush. */
    private const val LONG_SEGMENT = 180

    /**
     * Prepares [text] for the synthesiser. Returns the segments to speak in
     * order, each followed by its silence. Never returns blank segments.
     */
    fun shape(text: String, style: SpeechStyle = SpeechStyle.NATURALE): List<SpeechSegment> {
        val clean = normalize(text)
        if (clean.isBlank()) return emptyList()

        val s = style.coerced()
        fun pause(base: Long): Long =
            (base * s.expressiveness * s.pauseScale).toLong().coerceAtLeast(0)

        val out = ArrayList<SpeechSegment>()
        for (line in clean.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val isListItem = LIST_ITEM.containsMatchIn(line)
            val sentences = trimmed.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
            sentences.forEachIndexed { i, sentence ->
                val last = i == sentences.lastIndex
                val base = when {
                    last && isListItem -> LIST_PAUSE
                    last -> SENTENCE_PAUSE
                    else -> SENTENCE_PAUSE
                }
                val extra = if (sentence.length > LONG_SEGMENT) CLAUSE_PAUSE else 0L
                out += SpeechSegment(sentence, pause(base + extra))
            }
        }
        // Nothing to wait for after the final word.
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(pauseMs = 0)
        return out
    }

    /** The whole reply as one plain string, for engines without segment support. */
    fun plain(text: String): String = normalize(text).replace(Regex("""\s*\n\s*"""), ". ").trim()

    /**
     * Rewrites written conventions into speakable Italian. Order matters:
     * markup goes first, then abbreviations, then numbers.
     */
    fun normalize(text: String): String {
        var t = text

        // --- markup the eye reads and the ear should not hear ---------------
        t = t.replace(Regex("""```[\s\S]*?```"""), " ")          // code blocks
        t = t.replace(Regex("""`([^`]*)`"""), "$1")
        t = t.replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        t = t.replace(Regex("""\*([^*]+)\*"""), "$1")
        t = t.replace(Regex("""__([^_]+)__"""), "$1")
        t = t.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")   // links -> label
        t = t.replace(Regex("""^\s*#{1,6}\s*""", RegexOption.MULTILINE), "")
        t = t.replace(Regex("""^\s*[-*•]\s+""", RegexOption.MULTILINE), "")
        t = t.replace(Regex("""[“”«»"]"""), "")

        // --- abbreviations --------------------------------------------------
        for ((re, spoken) in ABBREVIATIONS) t = t.replace(re, spoken)

        // --- numbers the ear expects differently ----------------------------
        // 15:30 -> "15 e 30"; 15:00 -> "15"
        t = t.replace(Regex("""\b(\d{1,2}):00\b"""), "$1")
        t = t.replace(Regex("""\b(\d{1,2}):(\d{2})\b"""), "$1 e $2")
        // 12.5 -> "12,5" so Italian TTS reads a decimal comma, not a full stop.
        t = t.replace(Regex("""(\d)\.(\d)"""), "$1,$2")
        // Units read as words. These run BEFORE the symbol strip below, because
        // "°" is itself a \p{So} symbol and would otherwise be deleted first,
        // leaving "20 C" to be read as the letter C.
        t = t.replace(Regex("""(\d)\s*%"""), "$1 per cento")
        t = t.replace(Regex("""(\d)\s*°\s*C\b"""), "$1 gradi")
        t = t.replace(Regex("""(\d)\s*°"""), "$1 gradi")
        t = t.replace(Regex("""(\d)\s*€"""), "$1 euro")
        t = t.replace(Regex("""€\s*(\d)"""), "$1 euro")

        t = t.replace(Regex("""\p{So}"""), " ")                  // emoji & leftover symbols

        // --- whitespace ------------------------------------------------------
        t = t.replace(Regex("""[ \t]{2,}"""), " ")
        t = t.replace(Regex("""\n{2,}"""), "\n")
        return t.trim()
    }

    private val SENTENCE_END = Regex("""(?<=[.!?;:])\s+""")
    private val LIST_ITEM = Regex("""^\s*(?:[-*•]|\d+[.)])\s+""")

    /** Written short forms that must be spoken in full to sound natural. */
    private val ABBREVIATIONS: List<Pair<Regex, String>> = listOf(
        word("ecc\\.") to "eccetera",
        word("es\\.") to "per esempio",
        word("p\\.es\\.") to "per esempio",
        word("cioe'") to "cioè",
        word("n\\.") to "numero",
        word("ca\\.") to "circa",
        word("min") to "minuti",
        word("sec") to "secondi",
        word("km/h") to "chilometri orari",
        word("km") to "chilometri",
        word("kg") to "chili",
        word("gg") to "giorni",
        word("h") to "ore",
    )

    /**
     * Unicode-aware boundary. Java's `\b` is defined over `[A-Za-z0-9_]`, which
     * breaks next to accents and next to the dots inside "p.es." — the same trap
     * documented in ADR 0004.
     */
    private fun word(pattern: String) = Regex(
        """(?<![\p{L}\p{Nd}])(?:$pattern)(?![\p{L}\p{Nd}])""",
        RegexOption.IGNORE_CASE,
    )
}
