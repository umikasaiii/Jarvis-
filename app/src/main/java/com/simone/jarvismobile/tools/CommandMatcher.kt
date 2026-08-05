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
     * remembers [tool] + [missing], so the user's next reply completes the
     * action instead of starting over.
     */
    data class Ask(val question: String, val tool: String, val missing: String) : Match
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

        // --- Remember (checked before the calculator) ---------------------
        rememberContent(raw)?.let { return call("remember", "text" to it) }
        if (BARE_REMEMBER_RE.matches(t)) {
            return Match.Ask("Cosa vuoi che ricordi?", "remember", "text")
        }

        // --- Calculator ---------------------------------------------------
        CALC_RE.find(t)?.let { m ->
            val expr = m.groupValues[1].trim()
                .replace(" per ", "*").replace(" x ", "*")
                .replace(" diviso ", "/").replace(" piu ", "+").replace(" meno ", "-")
                .replace("per", "*").replace("diviso", "/")
            if (expr.any { it.isDigit() } && expr.all { it.isDigit() || it in "+-*/(). \t" }) {
                return call("calculate", "expression" to expr)
            }
        }

        return null
    }

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
