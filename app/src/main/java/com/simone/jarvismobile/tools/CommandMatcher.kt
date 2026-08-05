package com.simone.jarvismobile.tools

import com.simone.jarvismobile.core.protocol.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Maps plain Italian utterances to tool calls **deterministically**, before the
 * LLM is consulted.
 *
 * Why: a small on-device model is not reliable at emitting strict JSON tool
 * calls, and a mis-parsed command is worse than no command. These patterns are
 * explicit, testable and instant — the model is still used for everything that
 * isn't a recognised command, and it never gains the ability to call anything
 * outside the registry (docs/SECURITY.md §15).
 */
object CommandMatcher {

    private fun call(name: String, vararg args: Pair<String, String>): ToolCall =
        ToolCall(
            id = UUID.randomUUID().toString(),
            name = name,
            arguments = JsonObject(args.associate { it.first to JsonPrimitive(it.second) }),
            requiresConfirmation = false,
        )

    /** Returns a tool call for [utterance], or null when it isn't a command. */
    fun match(utterance: String): ToolCall? {
        val t = utterance.lowercase().trim().trim('.', '!', '?', ',')

        // --- Time / date -------------------------------------------------
        if (TIME_RE.containsMatchIn(t)) return call("get_time")

        // --- Battery -----------------------------------------------------
        if (BATTERY_RE.containsMatchIn(t)) return call("battery_status")

        // --- Flashlight --------------------------------------------------
        TORCH_ON_RE.find(t)?.let { return call("flashlight", "on" to "true") }
        TORCH_OFF_RE.find(t)?.let { return call("flashlight", "on" to "false") }

        // --- Timer -------------------------------------------------------
        TIMER_RE.find(t)?.let { m ->
            val value = m.groupValues[1].toIntOrNull() ?: return@let
            val unit = m.groupValues[2]
            val seconds = when {
                unit.startsWith("or") -> value * 3600
                unit.startsWith("min") -> value * 60
                else -> value
            }
            if (seconds in 1..86_400) return call("set_timer", "seconds" to seconds.toString())
        }

        // --- Alarm -------------------------------------------------------
        ALARM_RE.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h in 0..23 && min in 0..59) {
                return call("set_alarm", "hour" to h.toString(), "minute" to min.toString())
            }
        }

        // --- Calculator --------------------------------------------------
        CALC_RE.find(t)?.let { m ->
            val expr = m.groupValues[1].trim()
                .replace("per", "*").replace("x", "*")
                .replace("diviso", "/").replace("più", "+").replace("meno", "-")
            if (expr.isNotBlank() && expr.any { it.isDigit() } &&
                expr.all { it.isDigit() || it in "+-*/(). \t" }
            ) {
                return call("calculate", "expression" to expr)
            }
        }

        // --- Remember ----------------------------------------------------
        for (p in REMEMBER_PREFIXES) {
            if (t.startsWith(p)) {
                val content = utterance.trim().drop(p.length).trim().trim(':', '-', ' ')
                if (content.isNotEmpty()) return call("remember", "text" to content)
            }
        }

        return null
    }

    private val TIME_RE = Regex("""\b(che ore sono|che ora (è|e')|che giorno (è|e')|che data (è|e')|dimmi l'ora)\b""")
    private val BATTERY_RE = Regex("""\b(quanta batteria|livello (della )?batteria|stato (della )?batteria|quanto ha la batteria)\b""")
    private val TORCH_ON_RE = Regex("""\b(accendi|attiva) (la )?(torcia|flash)\b""")
    private val TORCH_OFF_RE = Regex("""\b(spegni|disattiva) (la )?(torcia|flash)\b""")
    private val TIMER_RE = Regex("""\b(?:timer|conto alla rovescia)\b[^0-9]{0,20}(\d{1,4})\s*(ore|ora|minuti|minuto|min|secondi|secondo|sec)\b""")
    private val ALARM_RE = Regex("""\b(?:sveglia|svegliami|sveglian?)\b[^0-9]{0,20}(\d{1,2})(?:[:.](\d{2}))?\b""")
    private val CALC_RE = Regex("""\b(?:calcola|quanto fa|quant'(?:è|e))\s+(.+)$""")

    /** Longest-first so "ricorda che " wins over "ricorda ". */
    private val REMEMBER_PREFIXES = listOf(
        "ricordati che ", "ricordati di ", "ricorda che ", "ricorda di ",
        "prendi nota che ", "prendi nota di ", "prendi nota ",
        "annota che ", "annota ", "nota che ", "ricorda ",
    )
}
