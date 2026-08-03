package com.simone.jarvismobile.core.routing

/**
 * Recognizes a small set of Italian commands that can be answered without ever
 * invoking the LLM (docs/ARCHITECTURE.md §13, step 1). Kept deliberately
 * conservative: only high-confidence, unambiguous phrasings match, so we never
 * short-circuit a genuine question.
 */
class DeterministicCommandMatcher {

    private data class Rule(val toolName: String, val patterns: List<Regex>)

    private val rules = listOf(
        Rule(
            "get_current_time",
            listOf(
                Regex("""\bche\s+ore\s+sono\b""", RegexOption.IGNORE_CASE),
                Regex("""\bche\s+ora\s+(è|e')\b""", RegexOption.IGNORE_CASE),
                Regex("""\bdimmi\s+l['e]?\s*ora\b""", RegexOption.IGNORE_CASE),
            ),
        ),
        Rule(
            "get_device_status",
            listOf(
                Regex("""\b(stato|livello)\s+(della\s+)?batteri\w*""", RegexOption.IGNORE_CASE),
                Regex("""\bquanta\s+batteria\b""", RegexOption.IGNORE_CASE),
            ),
        ),
        Rule(
            "get_connectivity_status",
            listOf(
                Regex("""\b(sono|siamo)\s+(online|offline|conness\w+)\b""", RegexOption.IGNORE_CASE),
                Regex("""\bc['e]?\s*(è\s+)?(internet|rete|connessione)\b""", RegexOption.IGNORE_CASE),
            ),
        ),
        Rule(
            "set_local_timer",
            listOf(
                Regex("""\b(imposta|metti|avvia)\s+un\s+timer\b""", RegexOption.IGNORE_CASE),
                Regex("""\btimer\s+(di|da)\s+\d+""", RegexOption.IGNORE_CASE),
            ),
        ),
    )

    private val homeAssistantHints = listOf(
        Regex("""\b(accendi|spegni|abbassa|alza|imposta)\b.*\b(luc\w+|termostato|riscaldamento|tapparell\w+|clima)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(chiudi|apri)\b.*\b(garage|serratura|porta|cancello)\b""", RegexOption.IGNORE_CASE),
    )

    fun match(transcript: String): DeterministicMatch? {
        val text = transcript.trim()
        if (text.isEmpty()) return null
        for (rule in rules) {
            if (rule.patterns.any { it.containsMatchIn(text) }) {
                return DeterministicMatch(rule.toolName, confidence = 0.95)
            }
        }
        return null
    }

    fun looksLikeHomeAssistant(transcript: String): Boolean =
        homeAssistantHints.any { it.containsMatchIn(transcript) }
}
