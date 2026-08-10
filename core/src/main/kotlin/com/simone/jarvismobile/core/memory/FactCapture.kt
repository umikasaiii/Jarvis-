package com.simone.jarvismobile.core.memory

/**
 * Decides whether a spontaneous user sentence states a *durable personal fact*
 * worth offering to remember — the "salva solo l'utile" side of contextual
 * memory. This never writes anything: it only recognises a candidate, which the
 * assistant then offers to save through the ordinary confirming-write path, so a
 * misread can never silently rewrite the vault.
 *
 * Deliberately conservative and deterministic (pure, unit-tested, offline): it
 * fires only on first-person statements that match a known "durable fact" shape
 * (name, home, birthday, work, health, stable tastes), and never on questions,
 * commands, secrets or things the user marked as temporary. Better to miss a
 * fact than to pester the user about every passing sentence.
 */
object FactCapture {

    data class Candidate(val fact: String, val kind: MemoryKind)

    fun detect(utterance: String): Candidate? {
        val text = utterance.trim().trimEnd('.', ' ', '!')
        if (text.length !in MIN_LEN..MAX_LEN) return null
        if ('?' in utterance) return null // a question is not a statement of fact
        if (MemoryStructure.containsCredential(text)) return null // never capture secrets
        val lower = text.lowercase()
        if (COMMAND_OR_QUESTION.containsMatchIn(lower)) return null
        if (DURABLE.none { it.containsMatchIn(lower) }) return null
        val base = MemoryStructure.classify(text)
        if (base == MemoryKind.TEMPORARY) return null // user asked not to keep it
        // A health fact (allergy, intolerance, condition) is personal-sensitive
        // even when the shared classifier doesn't already flag it, so it is stored
        // behind the 🔒 marker.
        val kind = if (base == MemoryKind.PERMANENT && HEALTH.containsMatchIn(lower)) {
            MemoryKind.SENSITIVE
        } else {
            base
        }
        return Candidate(text, kind)
    }

    private val HEALTH = Regex("""\b(allergic\w*|allergi\w*|intolleran\w*|celiac\w*|soffro di)\b""")

    private const val MIN_LEN = 6
    private const val MAX_LEN = 160

    /** Leading imperatives/interrogatives that mean "this isn't a fact to store". */
    private val COMMAND_OR_QUESTION = Regex(
        """^(ricord\w*|annota|segna|accendi|spegni|apri|chiudi|chiama|manda|invia|imposta|metti|""" +
            """crea|avvia|ferma|cerca|naviga|portami|calcola|dimmi|dammi|fammi|puoi|potresti|""" +
            """che|cosa|come|dove|quando|quanto|perch[éeè]|chi|qual\w*)(\s|$)""",
    )

    /**
     * First-person "durable fact" shapes. ASCII lead words use a plain word
     * boundary; anything that can trail into an accented letter is left open,
     * because Java's `\b` is not defined over accented characters (same caveat
     * as [MemoryStructure]).
     */
    private val DURABLE: List<Regex> = listOf(
        Regex("""\bmi chiamo\b"""),
        Regex("""\bil mio nome (è|e')"""),
        Regex("""\b(abito|vivo) (a|in|nei|nel|nella)\b"""),
        Regex("""\bsono nat[oa]\b"""),
        Regex("""\bil mio (compleanno|anniversario|onomastico)\b"""),
        Regex("""\bsono allergic\w*"""),
        Regex("""\bsoffro di\b"""),
        Regex("""\bsono (vegetarian\w+|vegan\w+|celiac\w+|intolleran\w+|astemi\w+)"""),
        Regex("""\b(lavoro come|lavoro (in|presso|per)|faccio il|faccio la|di mestiere)\b"""),
        Regex("""\bil mio (numero|indirizzo|medico|dottore|dentista|capo|compleanno) (è|e')"""),
        Regex("""\bla mia (auto|macchina|moto|targa|squadra|città|citta) (è|e')"""),
        Regex("""\b(mi piac\w+|adoro|amo|preferisco|non mi piac\w+|odio|detesto)\b"""),
        Regex("""\bho (un|una|due|tre) \w+ (che si chiama|chiamat[oa])"""),
        Regex("""\bsono (di|del|della) [a-zà-öø-þ]+"""), // "sono di Milano"
    )
}
