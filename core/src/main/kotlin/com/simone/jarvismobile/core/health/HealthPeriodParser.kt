package com.simone.jarvismobile.core.health

/**
 * § FASE 2A.7 RELEASE GATE 4 — real bug found by audit: `get_health_summary`
 * used to take NO temporal argument at all, so "Quanto ho dormito stanotte?",
 * "Quante ore ho dormito questa settimana?" and "Come ho dormito negli ultimi
 * 7 giorni?" all produced the exact same tool call and got back the exact
 * same weekly aggregate — "stanotte" was silently answered with the whole
 * week's average instead of last night's own reading. This is the
 * deterministic, generalized (never hardcoded-per-phrase) period detector:
 * capability detection stays separate from parameter extraction and
 * execution (§ FASE 2A.6 §3's three-step separation), so this is the one
 * place that decides WHICH window a health question means, reused by the
 * capability router to build the tool call's `period` argument.
 */
enum class HealthPeriod {
    /** Literally last night's own sleep reading — never averaged into the week. */
    LAST_NIGHT,

    /** The rolling weekly window — "questa settimana"/"ultimi 7 giorni"/"media" all mean the same aggregate. */
    WEEK,
}

object HealthPeriodParser {
    private val lastNightPatterns = listOf(
        "stanotte", "questa notte", "la notte scorsa", "notte scorsa", "ieri notte", "scorsa notte",
    ).map { Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE) }

    fun parse(text: String): HealthPeriod {
        val normalized = normalize(text)
        return if (lastNightPatterns.any { it.containsMatchIn(normalized) }) HealthPeriod.LAST_NIGHT else HealthPeriod.WEEK
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
}
