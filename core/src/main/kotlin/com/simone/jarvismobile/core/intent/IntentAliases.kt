package com.simone.jarvismobile.core.intent

/**
 * One declarative place for every synonym the assistant understands.
 *
 * Why a table and not scattered regexes: adding "posticipa" as another way to
 * say "sposta" must be a one-line data change, not an edit inside a parser. Each
 * group lists a canonical value and the words that mean it; lookups are built
 * once into a word→canonical map, so matching stays O(1) per token.
 *
 * Verb entries are matched by **stem** (prefix), because Italian conjugates:
 * "sposta", "spostare", "spostalo", "spostami", "spostiamo" all start with
 * "spost". Noun entries are matched as whole words.
 */
object IntentAliases {

    /** A canonical value plus the surface forms that map onto it. */
    data class Group<T>(val canonical: T, val forms: List<String>)

    // --- Verbs → Action ------------------------------------------------
    // Stems, longest-first at lookup time so "riprogramm" beats "ripr".
    private val ACTION_STEMS: List<Group<Action>> = listOf(
        Group(Action.DELETE, listOf("elimin", "cancell", "rimuov", "togli", "disdic", "annull")),
        Group(
            Action.MOVE,
            listOf("spost", "rimand", "postic", "anticip", "riprogramm", "slitt", "port"),
        ),
        Group(Action.UPDATE, listOf("modific", "cambi", "aggiorn", "corregg", "rinomin", "sostituisc")),
        Group(Action.CREATE, listOf("aggiung", "crea", "inseris", "segn", "fiss", "prenot", "program")),
        Group(Action.SEARCH, listOf("cerc", "trov", "ricerc")),
        Group(Action.LIST, listOf("elenc", "mostr", "list")),
        Group(Action.READ, listOf("legg", "dimm", "raccont")),
        Group(Action.OPEN, listOf("apr", "avvi", "lanci")),
        Group(Action.CLOSE, listOf("chiud", "termin")),
        Group(Action.STOP, listOf("ferm", "interromp", "stopp")),
        Group(Action.ENABLE, listOf("accend", "attiv", "abilit")),
        Group(Action.DISABLE, listOf("spegn", "disattiv", "disabilit")),
        Group(Action.PAUSE, listOf("paus")),
        Group(Action.PLAY, listOf("riprend", "riproduc", "suon")),
    )

    private val ACTION_BY_STEM: List<Pair<String, Action>> =
        ACTION_STEMS.flatMap { g -> g.forms.map { it to g.canonical } }
            .sortedByDescending { it.first.length }

    // --- Nouns → Domain ------------------------------------------------
    private val DOMAIN_WORDS: List<Group<Domain>> = listOf(
        Group(
            Domain.AGENDA,
            listOf(
                "appuntamento", "appuntamenti", "evento", "eventi", "impegno", "impegni",
                "riunione", "riunioni", "meeting", "agenda", "calendario", "promemoria",
                "attivita", "task", "visita", "scadenza", "scadenze",
            ),
        ),
        Group(
            Domain.NOTE,
            listOf(
                "appunto", "appunti", "nota", "note", "memo", "annotazione", "annotazioni",
                "promemoria scritto", "notes",
            ),
        ),
        Group(
            Domain.MEMORY,
            listOf("memoria", "ricordo", "ricordi", "informazione salvata"),
        ),
        Group(
            Domain.AUTOMATION,
            listOf("automazione", "automazioni", "regola", "regole", "routine"),
        ),
        Group(
            Domain.HOME_AUTOMATION,
            listOf(
                "luce", "luci", "lampada", "lampadina", "termostato", "riscaldamento",
                "tapparella", "tapparelle", "clima", "condizionatore", "presa", "prese",
            ),
        ),
        Group(Domain.MEDIA, listOf("musica", "canzone", "brano", "traccia", "playlist", "podcast", "album")),
        Group(Domain.TRANSLATION, listOf("traduzione", "traduttore", "traduci")),
    )

    private val DOMAIN_BY_WORD: Map<String, Domain> =
        DOMAIN_WORDS.flatMap { g -> g.forms.map { it to g.canonical } }.toMap()

    /**
     * Place aliases the user may configure ("lavoro" == "ufficio"). Kept here so
     * both navigation and note-filing resolve the same names; extend freely.
     */
    val PLACE_ALIASES: Map<String, List<String>> = linkedMapOf(
        "lavoro" to listOf("lavoro", "ufficio", "posto di lavoro", "job"),
        "casa" to listOf("casa", "abitazione", "domicilio"),
        "palestra" to listOf("palestra", "gym"),
    )

    /** Room aliases for home control ("camera" == "stanza" == "camera da letto"). */
    val ROOM_ALIASES: Map<String, List<String>> = linkedMapOf(
        "camera" to listOf("camera", "stanza", "camera da letto"),
        "cucina" to listOf("cucina"),
        "salotto" to listOf("salotto", "soggiorno", "sala"),
        "bagno" to listOf("bagno"),
        "studio" to listOf("studio", "ufficio"),
    )

    /**
     * Words that answer "yes" to a pending confirmation. Checked as a whole
     * first word or a whole leading phrase — never as a substring, so "sicuro
     * che no" is not read as "sicuro".
     */
    val AFFIRMATIVE: List<String> = listOf(
        "si", "sì", "certo", "certamente", "ok", "okay", "va bene", "vabene",
        "conferma", "confermo", "procedi", "fallo", "fai", "esatto", "giusto",
        "perfetto", "yes", "yep", "d'accordo", "daccordo", "andata", "avanti",
    )

    /** Words that answer "no" to a pending confirmation. */
    val NEGATIVE: List<String> = listOf(
        "no", "nope", "annulla", "annullare", "lascia stare", "lascia perdere",
        "non farlo", "non fare", "ferma", "stop", "niente", "nulla", "meglio di no",
        "aspetta", "fermati",
    )

    /**
     * Pronouns and demonstratives that stand in for the item just discussed:
     * "spostalo", "cancellala", "quello di domani". Enclitic pronouns are handled
     * separately (see [strippedEnclitic]) because they are glued to the verb.
     */
    val REFERENCE_WORDS: List<String> = listOf(
        "quello", "quella", "quelli", "quelle", "questo", "questa", "questi", "queste",
        "lo stesso", "la stessa", "ci", "li",
    )

    /** Enclitic object pronouns glued onto an imperative: spostA-LO, cancellA-LA. */
    private val ENCLITICS = listOf("melo", "mela", "glielo", "gliela", "lo", "la", "li", "le", "ne", "mi", "ti", "ci")

    /**
     * Splits an enclitic pronoun off an imperative verb, returning the bare verb
     * and whether a pronoun was found: "spostalo" → "sposta" + true.
     *
     * The remainder must still look like a complete Italian imperative — i.e.
     * end in a vowel. Without that check "cancella" was read as "cancel" + "la",
     * so a bare "cancella" looked like it was pointing at something and slipped
     * past the guard that refuses a delete with no target.
     */
    fun strippedEnclitic(word: String): Pair<String, Boolean> {
        for (e in ENCLITICS.sortedByDescending { it.length }) {
            if (!word.endsWith(e)) continue
            val bare = word.dropLast(e.length)
            if (bare.length >= 4 && bare.last() in IMPERATIVE_ENDINGS) return bare to true
        }
        return word to false
    }

    private val IMPERATIVE_ENDINGS = setOf('a', 'e', 'i')

    /** The action a verb (possibly conjugated or with an enclitic) means, if any. */
    fun actionOf(word: String): Action? {
        val bare = strippedEnclitic(word).first
        return ACTION_BY_STEM.firstOrNull { (stem, _) -> bare.startsWith(stem) || word.startsWith(stem) }?.second
    }

    /** The domain a noun names, if any. */
    fun domainOf(word: String): Domain? = DOMAIN_BY_WORD[word]

    /** First action verb found in an already-normalised sentence. */
    fun findAction(normalized: String): Action? =
        normalized.split(' ').firstNotNullOfOrNull { actionOf(it) }

    /** First domain noun found in an already-normalised sentence. */
    fun findDomain(normalized: String): Domain? =
        normalized.split(' ').firstNotNullOfOrNull { domainOf(it.trim(',', '.', ':', ';', '\'')) }

    /** Canonical name for an alias in [table], or null when unknown. */
    fun canonical(table: Map<String, List<String>>, text: String): String? {
        val t = TextNormalizer.normalize(text)
        return table.entries.firstOrNull { (_, forms) ->
            forms.any { form -> Regex("""(?<!\p{L})${Regex.escape(form)}(?!\p{L})""").containsMatchIn(t) }
        }?.key
    }

    /** True when the reply is an affirmative answer to a yes/no question. */
    fun isAffirmative(reply: String): Boolean = matchesVocabulary(reply, AFFIRMATIVE)

    /** True when the reply is a negative answer to a yes/no question. */
    fun isNegative(reply: String): Boolean = matchesVocabulary(reply, NEGATIVE)

    private fun matchesVocabulary(reply: String, vocabulary: List<String>): Boolean {
        val t = TextNormalizer.normalize(reply).trim('.', '!', ' ')
        if (t.isBlank()) return false
        return vocabulary.any { word ->
            t == word || t.startsWith("$word ") || t.startsWith("$word,")
        }
    }

    /**
     * True when a bare "cancella"/"annulla" during a pending confirmation means
     * "call the whole thing off" rather than "delete an object". The rule is
     * simple and safe: it only counts as a cancellation when nothing follows it
     * that could name a target.
     */
    fun isCancellationOfPendingAction(reply: String): Boolean {
        val t = TextNormalizer.normalize(reply).trim('.', '!', ' ')
        return t in CANCEL_ALONE || CANCEL_PHRASES.any { t == it }
    }

    private val CANCEL_ALONE = setOf(
        "cancella", "annulla", "elimina", "ferma", "stop", "lascia stare", "lascia perdere",
    )
    private val CANCEL_PHRASES = setOf(
        "cancella tutto", "annulla tutto", "cancella operazione", "annulla operazione",
        "cancella l'operazione", "annulla l'operazione", "non importa",
    )

    /**
     * True when the entire utterance is a bare "ok"/"okay" — the one thing a
     * wake word (or an accidentally-triggered listening session) can hand the
     * microphone to JARVIS with, with nothing the user actually wants to say.
     * Deliberately a separate check from [AFFIRMATIVE]/[isAffirmative]: "ok"
     * means "stop listening" here, but "yes, go ahead" when answering a real
     * pending confirmation — callers must only use this when nothing is
     * pending, never as a blanket replacement for [isAffirmative].
     */
    fun isListeningCancelWord(text: String): Boolean {
        val t = TextNormalizer.normalize(text).trim('.', '!', ' ')
        return t in LISTENING_CANCEL_WORDS
    }

    private val LISTENING_CANCEL_WORDS = setOf("ok", "okay")
}
