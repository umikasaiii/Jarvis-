package com.simone.jarvismobile.core.intent

import com.simone.jarvismobile.core.agenda.ItalianDateTimeParser
import java.time.LocalDateTime

/**
 * Understands CRUD-and-move phrasings over the personal planner without any
 * model: "sposta il dentista di domani a venerdì", "cancella la riunione delle
 * 18", "modifica dentista in visita dentistica", "spostalo di due ore".
 *
 * The hard part — and the reason this is its own tested unit — is telling the
 * item's *own* time from the *new* time it should move to. "Sposta il dentista
 * di domani a lunedì" names tomorrow as a way to pick the entry and Monday as
 * the destination; getting that backwards silently rewrites the wrong day. So
 * the destination clause is located first and cut out, and only what remains can
 * describe the target.
 *
 * The parser never resolves the target against the real planner: it returns the
 * user's own words plus any qualifiers, and the app layer decides between one
 * match, none, or a disambiguation question.
 */
object AgendaCommandParser {

    /**
     * Returns a [ResolvedIntent] for a planner CRUD/move phrase, or null when the
     * sentence isn't one. [now] anchors relative dates.
     */
    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): ResolvedIntent? {
        val norm = TextNormalizer.normalize(raw)
        if (norm.isBlank()) return null

        // "non voglio più l'appuntamento dal dentista" is a deletion stated as a
        // preference; handle it before verb lookup, which would find nothing.
        NOT_WANTED_RE.find(norm)?.let { m ->
            val rest = m.groupValues[1]
            val domain = domainOf(rest) ?: Domain.AGENDA
            val target = buildTarget(rest, now)
            if (target.isEmpty) return null
            return ResolvedIntent(
                domain = domain,
                action = Action.DELETE,
                score = 0.85f,
                source = IntentSource.STRUCTURED,
                rawText = raw,
                normalizedText = norm,
                target = target,
            )
        }

        // "segna X come completata" / "segna come completata l'attività X" /
        // "ho completato/fatto/finito X" / "spunta X" — checked before the
        // generic single-verb lookup below, since completion is a two-part
        // phrase (a verb plus a status cue) in either word order, which
        // findVerb()'s one-word-to-one-Action model can't express. "segna" by
        // itself already means Action.CREATE in the verb table (used elsewhere
        // for reminders/notes/lists), so this must be resolved as its own
        // pattern rather than by adding "segna" to a COMPLETE verb group.
        completeIntent(raw, norm, now)?.let { return it }

        // "quando ho il dentista" / "quando devo andare dal dentista" / "a che
        // ora è il dentista" / "che giorno ho il dentista" — a named lookup,
        // not the general "what's on today" listing (AGENDA_RE, matched
        // earlier in CommandMatcher, owns that). Also checked before the
        // generic verb lookup: there is no verb here at all in "quando è X".
        queryIntent(raw, norm, now)?.let { return it }

        val verb = findVerb(norm) ?: return null
        val action = verb.action
        if (action !in HANDLED) return null

        // Everything except the verb itself is what the request is about.
        val body = (norm.substring(0, verb.range.first) + " " + norm.substring(verb.range.last + 1))
            .replace(MULTISPACE, " ").trim()

        val domain = domainOf(norm)
        // A planner verb with no planner noun and no pronoun is too vague to own:
        // "cambia la lingua" must not become an agenda edit.
        if (domain == null && !verb.hasPronoun && !mentionsTime(body)) return null

        return when (action) {
            Action.MOVE -> parseMove(raw, norm, body, domain, verb, now)
            Action.DELETE -> parseDelete(raw, norm, body, domain, verb, now)
            Action.UPDATE -> parseUpdate(raw, norm, body, domain, verb, now)
            else -> null
        }
    }

    // --- MOVE ----------------------------------------------------------

    private fun parseMove(
        raw: String,
        norm: String,
        body: String,
        domain: Domain?,
        verb: Verb,
        now: LocalDateTime,
    ): ResolvedIntent? {
        // 1. A relative shift ("di due ore", "di mezz'ora") is unambiguous: it is
        //    always the change, never the target, so look for it first.
        relativeShift(body, verb.negativeShift)?.let { (minutes, without) ->
            val target = buildTarget(without, now)
            return intent(
                domain, Action.MOVE, raw, norm, target,
                IntentChanges(shiftMinutes = minutes),
                verb, scoreFor(target, domain, verb, changed = true),
            )
        }

        // 2. Otherwise the last temporal clause introduced by a destination marker
        //    is the new date/time; everything before it describes the target.
        val split = splitDestination(body, now) ?: run {
            // No explicit marker ("a"/"al"/"alle"/"per"/…) was found, but a bare
            // date/time may still be sitting in the target text: "sposta il
            // dentista venerdì" (real device phrasing — Italian regularly drops
            // "a" before a destination day). Treating that date as an
            // IDENTIFYING qualifier instead can only ever produce a MOVE with an
            // empty [IntentChanges], which `MoveAgendaTool.validate()` always
            // rejects ("manca la nuova data o il nuovo orario") — so this
            // branch never actually worked before either way, and reinterpreting
            // the date as the destination can only turn a previously-broken
            // phrasing into a working one.
            val target = buildTarget(body, now)
            if (target.date != null || target.time != null) {
                val changes = IntentChanges(date = target.date, time = target.time)
                val retargeted = target.copy(date = null, time = null)
                if (retargeted.isEmpty && !verb.hasPronoun) return null
                return intent(
                    domain, Action.MOVE, raw, norm, retargeted, changes, verb,
                    scoreFor(retargeted, domain, verb, changed = true),
                )
            }
            // "rimandalo" with no destination at all — recognised, but the app
            // has to ask when. Only worth returning if we know what to move.
            if (target.isEmpty && !verb.hasPronoun) return null
            return intent(
                domain, Action.MOVE, raw, norm, target, IntentChanges(), verb,
                score = 0.5f,
            )
        }
        val target = buildTarget(split.before, now)
        val changes = IntentChanges(date = split.date, time = split.time)
        if (changes.isEmpty) return null
        return intent(
            domain, Action.MOVE, raw, norm, target, changes, verb,
            scoreFor(target, domain, verb, changed = true),
        )
    }

    // --- DELETE --------------------------------------------------------

    private fun parseDelete(
        raw: String,
        norm: String,
        body: String,
        domain: Domain?,
        verb: Verb,
        now: LocalDateTime,
    ): ResolvedIntent? {
        val target = buildTarget(body, now)
        // A destructive verb with nothing to aim at is only meaningful when a
        // pronoun points at something already on the table.
        if (target.isEmpty && !verb.hasPronoun) return null
        return intent(
            domain, Action.DELETE, raw, norm, target, IntentChanges(), verb,
            scoreFor(target, domain, verb, changed = false),
        )
    }

    // --- UPDATE --------------------------------------------------------

    private fun parseUpdate(
        raw: String,
        norm: String,
        body: String,
        domain: Domain?,
        verb: Verb,
        now: LocalDateTime,
    ): ResolvedIntent? {
        // "modifica dentista in visita dentistica" / "cambialo in visita".
        RENAME_RE.find(body)?.let { m ->
            val before = body.substring(0, m.range.first).trim()
            val newTitle = m.groupValues[1].trim().trim('"', '«', '»', '.', ' ')
            if (newTitle.length >= 2) {
                val target = buildTarget(before, now)
                if (target.isEmpty && !verb.hasPronoun) return null
                return intent(
                    domain, Action.UPDATE, raw, norm, target,
                    IntentChanges(title = newTitle), verb,
                    scoreFor(target, domain, verb, changed = true),
                )
            }
        }

        // "cambia l'orario alle 16" / "cambia la data a venerdì" — an update whose
        // change is temporal is a move; keep one code path so the app has one
        // handler for "the entry's time changed".
        splitDestination(body, now)?.let { split ->
            val target = buildTarget(split.before, now)
            return intent(
                domain, Action.MOVE, raw, norm, target,
                IntentChanges(date = split.date, time = split.time), verb,
                scoreFor(target, domain, verb, changed = true),
            )
        }
        return null
    }

    // --- COMPLETE --------------------------------------------------------

    /**
     * "segna X come completata/fatta" / "segna come completata l'attività X" /
     * "ho completato/fatto/finito X" / "spunta X". Unlike the generic verb path
     * below, this is deliberately not gated on a planner noun or a day/time:
     * completion is never destructive (the router only ever finds a real entry
     * or says it found none — see [com.simone.jarvismobile.tools.AgendaIntentRouter]),
     * so an occasional false trigger on a stray "ho finito X" statement costs an
     * odd reply, not a wrong write — the same trade-off this exact pattern
     * already made before this parser existed.
     */
    private fun completeIntent(raw: String, norm: String, now: LocalDateTime): ResolvedIntent? {
        val strong = COMPLETE_STATUS_FIRST_RE.find(norm) ?: COMPLETE_TARGET_FIRST_RE.find(norm)
        val weak = if (strong == null) {
            COMPLETE_VERB_ONLY_RE.find(norm) ?: COMPLETE_SPUNTA_RE.find(norm)
        } else {
            null
        }
        val body = (strong ?: weak)?.groupValues?.getOrNull(1) ?: return null

        val target = buildTarget(body, now)
        if (target.isEmpty) return null
        return ResolvedIntent(
            domain = domainOf(norm) ?: Domain.AGENDA,
            action = Action.COMPLETE,
            score = if (strong != null) 0.85f else 0.65f,
            source = IntentSource.STRUCTURED,
            rawText = raw,
            normalizedText = norm,
            target = target,
        )
    }

    // --- QUERY -----------------------------------------------------------

    /**
     * "quando ho/è X" / "quando devo (andare) X" / "a che ora è X" / "che
     * giorno ho X" — asks when one specific, named item is, as opposed to
     * AGENDA_RE's "che impegni ho"/"cosa devo fare oggi" general listing
     * (matched earlier, in CommandMatcher, before this parser ever runs).
     * Read-only, so — like [completeIntent] — this is not gated on a planner
     * noun: the router either finds a real entry or says it found none.
     */
    private fun queryIntent(raw: String, norm: String, now: LocalDateTime): ResolvedIntent? {
        val body = QUERY_WHEN_RE.find(norm)?.groupValues?.getOrNull(1) ?: return null
        val target = buildTarget(body, now)
        if (target.isEmpty) return null
        return ResolvedIntent(
            domain = domainOf(norm) ?: Domain.AGENDA,
            action = Action.QUERY,
            score = 0.85f,
            source = IntentSource.STRUCTURED,
            rawText = raw,
            normalizedText = norm,
            target = target,
        )
    }

    // --- Target / destination extraction -------------------------------

    private data class Split(val before: String, val date: String?, val time: String?)

    /**
     * Finds the destination clause — the last "a / alle / al / per / entro"
     * marker whose tail actually parses as a date or time — and returns the text
     * before it plus that new date/time. Scanning right-to-left matters:
     * "sposta il dentista di domani a lunedì" must take "a lunedì", not the
     * earlier qualifier.
     */
    private fun splitDestination(body: String, now: LocalDateTime): Split? {
        val markers = DESTINATION_MARKER.findAll(body).toList()
        for (m in markers.asReversed()) {
            val tail = body.substring(m.range.last + 1).trim()
            if (tail.isBlank()) continue
            // Re-attach the marker for "alle 17"/"all'una": the date parser keys
            // on those words to read a clock time rather than a bare number.
            val parsed = ItalianDateTimeParser.parse(m.value.trim() + " " + tail, now)
            val date = parsed.date?.takeIf { parsed.dateExplicit }
            val time = parsed.time
            if (date == null && time == null) continue
            // The tail must be *about* the time: if the parser left a lot of
            // words over, this was not a destination clause ("porta il pane a
            // casa" is not a reschedule).
            if (parsed.remainder.trim().length > LEFTOVER_SLACK) continue
            return Split(
                before = body.substring(0, m.range.first).trim(),
                date = date?.toString(),
                time = time?.let { "%02d:%02d".format(it.hour, it.minute) },
            )
        }
        return null
    }

    /**
     * Reads "di due ore" / "di mezz'ora" / "di un giorno" as a signed number of
     * minutes, returning it with that clause removed. [negative] is set by verbs
     * that move an item earlier ("anticipa").
     */
    private fun relativeShift(body: String, negative: Boolean): Pair<Long, String>? {
        val m = RELATIVE_SHIFT_RE.find(body) ?: return null
        val amountWord = m.groupValues[1].trim()
        val unit = m.groupValues[2]
        val half = m.value.contains("mezz")
        val amount = when {
            half && amountWord.isBlank() -> 1L
            else -> amountWord.toLongOrNull() ?: NUMBER_WORDS[amountWord] ?: return null
        }
        val minutes = when {
            // "mezz'ora" first: the unit itself starts with "mezz", not "or".
            half -> 30L * amount
            unit.startsWith("minut") -> amount
            unit.startsWith("or") -> amount * 60L
            unit.startsWith("giorn") -> amount * 24 * 60
            unit.startsWith("settiman") -> amount * 7 * 24 * 60
            else -> return null
        }
        val without = (body.removeRange(m.range)).replace(MULTISPACE, " ").trim()
        return (if (negative) -minutes else minutes) to without
    }

    /**
     * Turns the descriptive part into a target: the user's own words for the
     * item, plus any date/time that narrows it down. Planner nouns are dropped
     * only when something else remains, so "elimina la riunione" still searches
     * for "riunione".
     */
    private fun buildTarget(text: String, now: LocalDateTime): IntentTarget {
        var t = text.replace(MULTISPACE, " ").trim().trim(',', '.', ':', ';', ' ')
        if (t.isBlank()) return IntentTarget()

        // A leading demonstrative ("quello del dentista") adds nothing to the
        // needle but does tell us the user is pointing at something.
        var pointed = false
        for (r in REFERENCE_PREFIX) {
            val m = r.find(t) ?: continue
            if (m.range.first == 0) {
                t = t.removeRange(m.range).trim()
                pointed = true
            }
        }

        // "l'impegno delle 18" states the entry's own hour. The date parser keys
        // on "alle/ore", so rewrite the possessive form before handing it over —
        // otherwise the 18 survives as a nonsense needle instead of a qualifier.
        t = t.replace(POSSESSIVE_HOUR, "alle ")

        val parsed = ItalianDateTimeParser.parse(t, now)
        val qualifierDate = parsed.date?.takeIf { parsed.dateExplicit }?.toString()
        val qualifierTime = parsed.time?.let { "%02d:%02d".format(it.hour, it.minute) }
        var needle = parsed.remainder.trim()

        val stripped = needle.replace(PLANNER_NOUNS, " ")
            .replace(LEADING_PREPOSITIONS, " ")
            .replace(MULTISPACE, " ")
            .trim().trim(',', '.', ':', ';', '\'', ' ')
        // Keep the noun when it is all the user gave us.
        needle = if (stripped.length >= 2) stripped else needle.replace(LEADING_PREPOSITIONS, " ")
            .replace(MULTISPACE, " ").trim().trim(',', '.', ':', ';', '\'', ' ')

        return IntentTarget(
            needle = needle,
            date = qualifierDate,
            time = qualifierTime,
            fromContext = pointed,
        )
    }

    // --- Verb lookup ---------------------------------------------------

    private data class Verb(
        val action: Action,
        val range: IntRange,
        val hasPronoun: Boolean,
        val negativeShift: Boolean,
    )

    /** Finds the first word that is a known action verb, with its position. */
    private fun findVerb(norm: String): Verb? {
        var index = 0
        for (word in norm.split(' ')) {
            if (word.isNotBlank()) {
                val action = IntentAliases.actionOf(word)
                if (action != null) {
                    val (bare, enclitic) = IntentAliases.strippedEnclitic(word)
                    return Verb(
                        action = action,
                        range = index until (index + word.length),
                        hasPronoun = enclitic,
                        negativeShift = bare.startsWith("anticip"),
                    )
                }
            }
            index += word.length + 1
        }
        return null
    }

    private fun domainOf(text: String): Domain? {
        val words = text.split(' ', ',', '.', '\'').map { it.trim() }.filter { it.isNotBlank() }
        // Notes win over agenda when both are named, because "nota" is the more
        // specific word: "aggiungi una nota all'evento" is about the note.
        words.firstNotNullOfOrNull { w -> IntentAliases.domainOf(w)?.takeIf { it == Domain.NOTE } }
            ?.let { return it }
        return words.firstNotNullOfOrNull { w ->
            IntentAliases.domainOf(w)?.takeIf { it == Domain.AGENDA || it == Domain.MEMORY }
        }
    }

    private fun mentionsTime(text: String): Boolean = TIME_HINT.containsMatchIn(text)

    private fun intent(
        domain: Domain?,
        action: Action,
        raw: String,
        norm: String,
        target: IntentTarget,
        changes: IntentChanges,
        verb: Verb,
        score: Float,
    ) = ResolvedIntent(
        domain = domain ?: Domain.AGENDA,
        action = action,
        score = score,
        source = if (domain == null && verb.hasPronoun) IntentSource.CONTEXT else IntentSource.STRUCTURED,
        rawText = raw,
        normalizedText = norm,
        target = target.copy(fromContext = target.fromContext || verb.hasPronoun),
        changes = changes,
        entities = if (domain == null) mapOf("domain_inferred" to "true") else emptyMap(),
    )

    /**
     * How sure we are. Naming the item explicitly is what earns HIGH; a bare
     * pronoun stays MEDIUM so the app resolves it from context and can still ask.
     */
    private fun scoreFor(target: IntentTarget, domain: Domain?, verb: Verb, changed: Boolean): Float {
        var s = 0.55f
        if (target.needle.isNotBlank()) s += 0.20f
        if (target.date != null || target.time != null) s += 0.08f
        if (domain != null) s += 0.10f
        if (changed) s += 0.05f
        if (target.needle.isBlank() && verb.hasPronoun) s -= 0.05f
        return s.coerceIn(0f, 0.98f)
    }

    private val HANDLED = setOf(Action.MOVE, Action.DELETE, Action.UPDATE)

    private val MULTISPACE = Regex("""\s+""")

    /** "delle 18" / "dell'una" / "dalle 9" — an entry's own hour, not a destination. */
    private val POSSESSIVE_HOUR = Regex("""(?<!\p{L})(?:dell[ea]|dell'|dalle)\s*(?=\d)""")

    /** Words that may introduce the new date/time of a move. */
    private val DESTINATION_MARKER = Regex(
        """(?<!\p{L})(?:a|ad|al|allo|alla|alle|all'|per|entro|verso)(?!\p{L})\s*""",
    )

    /** How much non-temporal text may survive in a destination clause. */
    private const val LEFTOVER_SLACK = 3

    private val RELATIVE_SHIFT_RE = Regex(
        """(?<!\p{L})(?:di|per)\s+(?:(\d{1,3}|un|uno|una|due|tre|quattro|cinque|sei|sette|otto|nove|dieci)\s+)?""" +
            """(mezz'?or[ae]|or[ae]|minut\w*|giorn\w*|settiman\w*)(?!\p{L})""",
    )

    private val NUMBER_WORDS = mapOf(
        "un" to 1L, "uno" to 1L, "una" to 1L, "due" to 2L, "tre" to 3L, "quattro" to 4L,
        "cinque" to 5L, "sei" to 6L, "sette" to 7L, "otto" to 8L, "nove" to 9L, "dieci" to 10L,
    )

    /** "modifica X in Y" / "cambia X con Y" — the new title follows. */
    private val RENAME_RE = Regex(
        """(?<!\p{L})(?:in|con|come)\s+(?:"|«)?([^"»]{2,60})(?:"|»)?\s*$""",
    )

    private val NOT_WANTED_RE = Regex(
        """(?<!\p{L})non\s+(?:voglio|vorrei|mi\s+serve)\s+(?:piu\s+)?(.+)$""",
    )

    /** "segna X come completata/fatta/conclusa" — target named before the status. */
    private val COMPLETE_TARGET_FIRST_RE = Regex(
        """^(?:segna|imposta|considera)\w*\s+(.+?)\s+come\s+(?:fatt[oa]|completat[oa]|conclus[oa])$""",
    )

    /** "segna come completata/fatta l'attività X" — status words before the target. */
    private val COMPLETE_STATUS_FIRST_RE = Regex(
        """^(?:segna|imposta|considera)\w*\s+come\s+(?:fatt[oa]|completat[oa]|conclus[oa])\s+(.+)$""",
    )

    /** "(ho) completato/fatto/finito X" — a weaker signal, gated in [completeIntent]. */
    private val COMPLETE_VERB_ONLY_RE = Regex(
        """^(?:ho\s+)?(?:completat[oa]|fatto|finito)\s+(.+)$""",
    )

    /** "spunta X" — same weak-signal gating as [COMPLETE_VERB_ONLY_RE]. */
    private val COMPLETE_SPUNTA_RE = Regex("""^spunta\w*\s+(.+)$""")

    /**
     * "quando ho/è X" / "quando devo (andare) X" / "a che ora è X" / "che
     * giorno ho X". "andare" is folded into the fixed prefix (not the capture)
     * so "quando devo andare dal dentista" targets "dal dentista", not
     * "andare dal dentista" — buildTarget()/TextNormalizer.matches() require
     * every needle word to appear in the saved title, so a stray "andare"
     * would otherwise sink an exact match.
     */
    private val QUERY_WHEN_RE = Regex(
        """^(?:quando|a\s+che\s+ora|che\s+giorno)\s+(?:ho|e|c'?e|devo(?:\s+andare)?)\s+(.+)$""",
    )

    private val REFERENCE_PREFIX = listOf(
        Regex("""^(?:quell[oaie]|quest[oaie])\s+(?:d[eia]l?l?[oaie']?\s*)?"""),
        Regex("""^(?:lo\s+stesso|la\s+stessa)\s+"""),
    )

    /** Planner nouns removed from a needle when other words remain. */
    // NB: "scadenza"/"scadenze" is deliberately NOT here. Unlike "appuntamento"/
    // "impegno"/"riunione" (generic words for "a thing on the calendar"),
    // "scadenza" usually names WHAT is due ("scadenza IVA", "controllare le
    // scadenze") — stripping it as filler can throw away the one word that
    // still matches the entry's real saved title.
    private val PLANNER_NOUNS = Regex(
        """(?<!\p{L})(?:l')?(?:appuntament[oi]|event[oi]|impegn[oi]|riunion[ei]|meeting|""" +
            """promemoria|attivit[àa]|task|agenda|calendario|""" +
            """appunt[oi]|not[ae]|memo|annotazion[ei])(?!\p{L})""",
    )

    private val LEADING_PREPOSITIONS = Regex(
        """(?<!\p{L})(?:il|lo|la|i|gli|le|un|uno|una|l'|dell'|del|dello|della|dei|degli|delle|""" +
            """dal|dallo|dalla|dai|sul|sulla|su|di|da|a|in|con|per)(?!\p{L})""",
    )

    private val TIME_HINT = Regex(
        """(?<!\p{L})(?:oggi|domani|dopodomani|stasera|stamattina|lunedi|martedi|mercoledi|giovedi|""" +
            """venerdi|sabato|domenica|alle|all'|ore|orario|data|giorno|settimana)(?!\p{L})""",
    )
}
