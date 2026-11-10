package com.simone.jarvismobile.core.tools

/** Phone numbers exactly as the user said/wrote them — never invented, never taken from a model. */
object PhoneNumbers {
    private val PHONE = Regex("""(?<!\w)\+?\d[\d ()-]{1,28}\d(?!\w)""")

    /** The first phone-like number in [raw] with separators removed, or null. 3–15 digits, as the dialer tool requires. */
    fun extract(raw: String): String? = PHONE.find(raw)?.value
        ?.trim()?.replace(Regex("""[ ()-]"""), "")
        ?.takeIf { it.count(Char::isDigit) in 3..15 }
}

/**
 * BETA 8 hotfix 2 — the strict *hard-command* grammar for "prepare a call / an SMS".
 *
 * Why it exists: the previous matcher fired on the mere presence of the word `chiama` or `telefon…` ANYWHERE in the
 * sentence. "Come si chiama la capitale…", "…quando spengo il telefono?", "Ricorda che il mio animale si chiama Nebulix"
 * were all answered with «Quale numero devo preparare nel dialer?» before the language model ever saw them (reproduced
 * with the real `CommandMatcher`). That is a hidden keyword router (Master §4.19, §11.6).
 *
 * Master §11 allows regex only for "date, unità, ID e grammar strettissime di hard commands". This is the latter: the
 * request must OPEN with an explicit imperative/infinitive (after at most a greeting or a polite word), so a verb or
 * noun that merely occurs inside a statement or a question can never authorise a phone action. The text is expected
 * already normalised (lower case, accents stripped, polite prefixes removed) by the caller.
 */
object PhoneCommandGrammar {
    private const val LEAD =
        """(?:(?:ciao|salve|ehi|hey|ok|okay|jarvis|scusa|senti|dai|allora|per\s+favore|per\s+piacere)[\s,]+)*"""

    private val dial = Regex(
        """^$LEAD(?:(?:chiama|telefona)(?:re)?\b|prepara\w*\s+(?:una\s+)?chiamat\w*|apri\w*\s+(?:il\s+)?dialer\b)""",
    )
    private val smsLead = Regex("""^$LEAD(?:(?:prepara|componi|manda|invia|scrivi)\w*|sms)\b""")
    private val smsWord = Regex("""\bsms\b""")
    private val messageNoun = Regex("""\b(?:sms|messaggio)\b""")
    private val callVocabulary = Regex("""\b(?:chiama\w*|telefon\w*|dialer|squill\w*)\b""")

    /** "chiama Marco", "telefonare al 061…", "prepara una chiamata", "apri il dialer". Never "si chiama", never "il telefono". */
    fun isDialRequest(normalized: String): Boolean = dial.containsMatchIn(normalized)

    /**
     * "prepara un SMS al 333…", "manda un messaggio al 333…". The sentence must open with a sending verb (or "sms") AND
     * either name an SMS or carry a phone number — so "scrivi un messaggio di auguri per mia madre" stays a writing
     * request for the model.
     */
    fun isSmsRequest(normalized: String, hasPhoneNumber: Boolean): Boolean {
        if (!smsLead.containsMatchIn(normalized)) return false
        return smsWord.containsMatchIn(normalized) || (hasPhoneNumber && messageNoun.containsMatchIn(normalized))
    }

    /**
     * A model (classifier) may only PROPOSE a call/SMS for a request the grammar did not recognise if the current turn
     * itself carries consistent evidence: a phone number AND call/message vocabulary. A name and a score are not
     * evidence (Master §4.2: model output ≠ authorization).
     */
    fun modelProposedCallHasEvidence(normalized: String, hasPhoneNumber: Boolean): Boolean =
        hasPhoneNumber && callVocabulary.containsMatchIn(normalized)

    fun modelProposedSmsHasEvidence(normalized: String, hasPhoneNumber: Boolean): Boolean =
        hasPhoneNumber && messageNoun.containsMatchIn(normalized)
}

enum class SlotAnswerVerdict { ANSWER, EXPIRED, NOT_AN_ANSWER }

/**
 * BETA 8 hotfix 2 — a question JARVIS asked ("Quale numero devo preparare nel dialer?") must not trap the next,
 * independent message. Before this, the reply to a pending slot was always fed to the slot filler: for a call it
 * re-asked the same question forever, for a destination/query it silently turned a whole unrelated sentence into the
 * value (and e.g. started a navigation to "Ricorda che il mio animale…").
 *
 * A reply counts as an ANSWER only if it has the shape of the value that was asked for; otherwise the pending question
 * is dropped and the message is handled as a new, independent request (Master §4.1/§10.2: the explicit current turn
 * wins over previous context). This is a structural check on the *shape of an answer*, not an interpretation of meaning.
 */
object SlotAnswerPolicy {
    const val TTL_MS = 120_000L
    private const val MAX_SHORT_VALUE_WORDS = 10
    private val numberSlots = setOf("prepare_call", "compose_sms")
    private val shortValueTools = setOf("navigate", "play_media", "search_vault", "open_app", "open_settings")

    fun assess(tool: String, missing: String, reply: String, askedAgoMs: Long): SlotAnswerVerdict {
        if (askedAgoMs > TTL_MS) return SlotAnswerVerdict.EXPIRED
        val r = reply.trim()
        if (r.isEmpty()) return SlotAnswerVerdict.NOT_AN_ANSWER
        if (tool in numberSlots && missing == "number") {
            return if (PhoneNumbers.extract(r) != null) SlotAnswerVerdict.ANSWER else SlotAnswerVerdict.NOT_AN_ANSWER
        }
        // A question is a new request, never the value of a slot.
        if (r.endsWith("?")) return SlotAnswerVerdict.NOT_AN_ANSWER
        if (tool in shortValueTools && r.split(Regex("""\s+""")).size > MAX_SHORT_VALUE_WORDS) return SlotAnswerVerdict.NOT_AN_ANSWER
        return SlotAnswerVerdict.ANSWER
    }

    /** Asking the very same slot twice in a row is not progress: stop and let the conversation continue. */
    fun isRepeatOfSameQuestion(askedTool: String, askedMissing: String, nextTool: String, nextMissing: String): Boolean =
        askedTool == nextTool && askedMissing == nextMissing
}
