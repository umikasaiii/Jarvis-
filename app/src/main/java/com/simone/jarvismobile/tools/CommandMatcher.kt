package com.simone.jarvismobile.tools

import com.simone.jarvismobile.core.protocol.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** What the matcher made of an utterance. */
sealed interface Match {
    /** A tool to run right away. */
    data class Run(val call: ToolCall) : Match

    /**
     * Recognised the intent but a detail is missing. JARVIS asks [question] and
     * remembers [tool] + [missing] (plus anything already understood in
     * [partial]), so the user's next reply completes the action instead of
     * starting over.
     */
    data class Ask(
        val question: String,
        val tool: String,
        val missing: String,
        val partial: Map<String, String> = emptyMap(),
    ) : Match
}

/**
 * Maps plain Italian utterances to tool calls **deterministically**, before the
 * LLM is consulted.
 *
 * Why: a small on-device model is not reliable at emitting strict JSON tool
 * calls, and a mis-parsed command is worse than no command. These patterns are
 * explicit, testable and instant — the model is still used for everything that
 * isn't a recognised command, and it never gains the ability to call anything
 * outside the registry (docs/SECURITY.md §15).
 *
 * Matching is intentionally tolerant: polite wrappers ("puoi …", "potresti …",
 * "per favore") are stripped, and verbs are matched by stem so both
 * "accendi la torcia" and "puoi accendere la torcia" work.
 */
object CommandMatcher {

    private fun call(name: String, vararg args: Pair<String, String>): Match.Run =
        Match.Run(
            ToolCall(
                id = UUID.randomUUID().toString(),
                name = name,
                arguments = JsonObject(args.associate { it.first to JsonPrimitive(it.second) }),
                requiresConfirmation = false,
            ),
        )

    /** Returns a [Match] for [utterance], or null when it isn't a command. */
    fun match(utterance: String): Match? {
        val raw = utterance.trim()
        val t = normalize(raw)

        // --- Time / date -------------------------------------------------
        if (TIME_RE.containsMatchIn(t)) return call("get_time")

        // --- Battery -----------------------------------------------------
        if (BATTERY_RE.containsMatchIn(t)) return call("battery_status")

        // --- Flashlight --------------------------------------------------
        if (TORCH_RE.containsMatchIn(t)) {
            return if (TORCH_OFF_RE.containsMatchIn(t)) {
                call("flashlight", "on" to "false")
            } else {
                call("flashlight", "on" to "true")
            }
        }

        // --- Timer -------------------------------------------------------
        if (TIMER_WORD_RE.containsMatchIn(t) || COUNTDOWN_RE.containsMatchIn(t)) {
            ItalianNumbers.duration(t)?.let {
                return call("set_timer", "seconds" to it.toString())
            }
            return Match.Ask("Per quanto tempo?", "set_timer", "seconds")
        }

        // --- Alarm -------------------------------------------------------
        if (ALARM_WORD_RE.containsMatchIn(t)) {
            ItalianNumbers.clockTime(t)?.let { (h, m) ->
                return call("set_alarm", "hour" to h.toString(), "minute" to m.toString())
            }
            return Match.Ask("A che ora?", "set_alarm", "time")
        }

        // --- Recall what was noted down -----------------------------------
        if (RECALL_RE.containsMatchIn(t)) return call("list_memories")

        // --- Remember (checked before the calculator) ---------------------
        rememberContent(raw)?.let { note ->
            // A reminder without a date is barely useful, so ask rather than
            // storing something we can't surface at the right time.
            return if (hasTimeReference(t)) {
                call("remember", "text" to note)
            } else {
                Match.Ask("Quando?", "remember", "when", mapOf("text" to note))
            }
        }
        if (BARE_REMEMBER_RE.matches(t)) {
            return Match.Ask("Cosa vuoi che ricordi?", "remember", "text")
        }

        // --- Calculator ---------------------------------------------------
        arithmetic(raw)?.let { return call("calculate", "expression" to it) }

        return null
    }

    /**
     * Extracts an arithmetic expression from the user's OWN words.
     *
     * This is deliberately never delegated to the model: asked to echo "5 * 2"
     * a small model may write "7 * 2", silently changing the question. Digits
     * must come from the user, so the answer is to the sum actually asked.
     * Returns null when there is nothing safely computable.
     */
    fun arithmetic(raw: String): String? {
        var t = raw.lowercase()
            .replace('à', 'a').replace('è', 'e').replace('é', 'e')
            .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
            .trim().trim('?', '!', '.', ' ')

        // Drop a leading "quanto fa" / "calcola" if present.
        t = t.replace(Regex("""^(calcola|quanto fa|quant'?e|fammi il calcolo di)\s+"""), "")

        // Spoken operators → symbols (word boundaries only, so "percento" is safe).
        t = t
            .replace(Regex("""\bper\b"""), "*")
            .replace(Regex("""\bx\b"""), "*")
            .replace(Regex("""\bdiviso\b"""), "/")
            .replace(Regex("""\bpiu\b"""), "+")
            .replace(Regex("""\bmeno\b"""), "-")
            .replace("×", "*").replace("÷", "/").replace(",", ".")

        // Keep only characters that belong in an expression.
        val expr = t.filter { it.isDigit() || it in "+-*/(). " }.trim()

        // Must contain a real operation between numbers, not just a stray digit.
        if (!Regex("""\d\s*[+\-*/]\s*\(?\s*\d""").containsMatchIn(expr)) return null
        if (!expr.all { it.isDigit() || it in "+-*/(). " }) return null
        return expr
    }

    /** Public so callers can reuse the reminder-body extraction. */
    fun rememberBody(raw: String): String? = rememberContent(raw)

    /**
     * Strips accents and polite wrappers so one pattern covers many phrasings.
     * "Puoi accendere la torcia?" -> "accendere la torcia"
     */
    private fun normalize(s: String): String {
        var t = s.lowercase()
            .replace('à', 'a').replace('è', 'e').replace('é', 'e')
            .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
            .trim().trim('.', '!', '?', ',')
        var changed = true
        while (changed) {
            changed = false
            for (p in POLITE_PREFIXES) {
                if (t.startsWith(p)) {
                    t = t.removePrefix(p).trim()
                    changed = true
                }
            }
        }
        return t.replace(" per favore", "").replace("per favore ", "").trim()
    }

    /** Extracts the note body of a "remember this" command, preserving case. */
    private fun rememberContent(raw: String): String? {
        val lower = normalize(raw)
        for (p in REMEMBER_PREFIXES) {
            if (lower.startsWith(p)) {
                // Map the normalized offset back onto the original text as best we
                // can: take the same number of trailing characters.
                val body = lower.removePrefix(p).trim().trim(':', '-', ' ')
                if (body.isEmpty()) return null
                val original = raw.takeLast(body.length).trim()
                return if (original.length == body.length) original else body
            }
        }
        return null
    }

    private val POLITE_PREFIXES = listOf(
        "puoi ", "potresti ", "per favore ", "mi ", "ti ", "jarvis ", "ehi ", "hey ",
        "vorrei che ", "voglio che ", "riesci a ", "sai ",
    )

    private val TIME_RE = Regex("""\b(che ore sono|che ora (e|e')|ora esatta|che giorno (e|e')|che data (e|e')|dimmi l'ora|dimmi che ore)\b""")
    private val BATTERY_RE = Regex("""\bbatteri[ae]\b""")

    // Any torch verb + the word torcia/flash, in either order.
    private val TORCH_RE = Regex("""\b(accend\w*|attiv\w*|spegn\w*|disattiv\w*)\b.{0,20}\b(torcia|flash)\b|\b(torcia|flash)\b.{0,20}\b(accend\w*|attiv\w*|spegn\w*|disattiv\w*)\b""")
    private val TORCH_OFF_RE = Regex("""\b(spegn\w*|disattiv\w*)\b""")

    private val TIMER_WORD_RE = Regex("""\b(timer|conto alla rovescia)\b""")

    /** "avvisami fra 10 minuti", "ricordamelo tra un'ora" — a countdown by any name. */
    private val COUNTDOWN_RE = Regex("""\b(avvis\w*|chiam\w*|sveglia\w*)\b.{0,15}\b(fra|tra)\b|\b(fra|tra)\b.{0,25}\b(minut\w*|second\w*|or[ae])\b""")

    private val ALARM_WORD_RE = Regex("""\b(sveglia|svegliami|sveglie|destami)\b""")

    /** A bare "ricorda"/"prendi nota" with nothing to store yet. */
    private val BARE_REMEMBER_RE = Regex("""^(ricorda(mi|ti)?|prendi (una )?nota|annota|segna(ti)?)\s*$""")

    /** "che impegni ho", "cosa devo fare", "cosa hai segnato" → read the notes back. */
    private val RECALL_RE = Regex("""\b(che|quali)\s+(impegni|appuntamenti|cose|promemoria)\b|\bcosa\s+(devo|ho da)\s+fare\b|\bcosa\s+(hai|ho)\s+(segnato|annotato|scritto)\b|\bi miei (impegni|promemoria|appunti)\b|\bcosa mi ricordi\b""")

    /** Does the note already say WHEN? Used to decide whether to ask. */
    fun hasTimeReference(text: String): Boolean = TIME_REFERENCE_RE.containsMatchIn(text.lowercase())

    private val TIME_REFERENCE_RE = Regex(
        """\b(oggi|domani|dopodomani|stasera|stamattina|stanotte|adesso|subito|""" +
            """luned[ìi]|marted[ìi]|mercoled[ìi]|gioved[ìi]|venerd[ìi]|sabato|domenica|""" +
            """gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre|""" +
            """settimana|mese|weekend|fine settimana|prossim\w+|""" +
            """(tra|fra)\s+\w+\s+(giorn\w+|settiman\w+|mes\w+|or[ae]|minut\w+)|""" +
            """\bil\s+\d{1,2}\b|\bentro\b|\balle\s+\d{1,2}\b)""",
    )

    private val CALC_RE = Regex("""\b(?:calcola|quanto fa|quant'?e)\s+(.+)$""")

    /** Longest-first so "ricordami che " wins over "ricorda ". */
    private val REMEMBER_PREFIXES = listOf(
        "ricordami che ", "ricordami di ", "ricordati che ", "ricordati di ",
        "ricorda che ", "ricorda di ", "ricordami ", "ricordati ",
        "prendi nota che ", "prendi nota di ", "prendi nota ",
        "segna che ", "segnati che ", "segna ",
        "annota che ", "annota ", "nota che ", "ricorda ",
    )
}
