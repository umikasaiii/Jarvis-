package com.simone.jarvismobile.core.routing

data class PlannedRequest(
    val text: String,
    val index: Int,
    /** A later clause may depend on the topic/result introduced before it. */
    val carriesTurnContext: Boolean,
    val needsReasoningFallback: Boolean,
)

data class TurnPlan(
    val original: String,
    val requests: List<PlannedRequest>,
) {
    val isCompound: Boolean get() = requests.size > 1
}

/**
 * Deterministic first pass of Understanding V2.
 *
 * It only finds independent requests that each need an answer. The local model
 * still decides the intent of every clause. Keeping the decomposition outside a
 * 1B model prevents it from silently answering just the first of several
 * questions, while [carriesTurnContext] lets later clauses resolve phrases such
 * as "quanto dura?" or "ne ho uno in programma?".
 */
object TurnPlanner {
    private val nextRequest = Regex(
        """(?i)^(?:e\s+)?(?:quanto|come|quando|dove|perch[eé]|quale|quali|chi|cosa|""" +
            """ogni\s+quanto|di\s+cosa|ne\s+ho|ho\s+(?:un|una|degli|delle)|""" +
            """dimmi|controlla|verifica|calcola|accendi|spegni|imposta|avvia|""" +
            """aggiungi|ricordami|segnami|mostrami|elenca)\b""",
    )

    fun plan(text: String, maxParts: Int = 6): TurnPlan {
        val input = text.trim()
        if (input.isEmpty()) return TurnPlan(text, emptyList())

        val found = splitAtHardBoundaries(input)
            .flatMap(::splitAtRequestJoiners)
            .map(String::trim)
            .filter { it.any(Char::isLetterOrDigit) }
        val parts = when {
            found.size <= 1 -> listOf(input)
            found.size <= maxParts.coerceAtLeast(2) -> found
            else -> {
                val limit = maxParts.coerceAtLeast(2)
                found.take(limit - 1) + found.drop(limit - 1).joinToString(" ")
            }
        }

        return TurnPlan(
            original = input,
            requests = parts.mapIndexed { index, part ->
                PlannedRequest(
                    text = part,
                    index = index,
                    carriesTurnContext = index > 0,
                    needsReasoningFallback = ComplexityHeuristic.needsReasoning(part),
                )
            },
        )
    }

    private fun splitAtHardBoundaries(input: String): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        input.forEachIndexed { index, char ->
            if (char != '?' && char != ';' && char != '\n') return@forEachIndexed
            val end = if (char == '?') index + 1 else index
            val part = input.substring(start, end).trim()
            if (part.any(Char::isLetterOrDigit)) parts += part
            start = index + 1
        }
        val tail = input.substring(start).trim()
        if (tail.any(Char::isLetterOrDigit)) parts += tail
        return if (parts.isEmpty()) listOf(input) else parts
    }

    /**
     * A comma or "e" is a boundary only when the following words clearly begin
     * a new question/action. Comparisons such as "elettrico e manuale" stay one
     * request.
     */
    private fun splitAtRequestJoiners(text: String): List<String> {
        val results = ArrayList<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            val candidates = buildList {
                Regex(""",\s*""").findAll(remaining).forEach { add(it.range.first to it.range.last + 1) }
                Regex("""(?i)\s+e\s+""").findAll(remaining).forEach { add(it.range.first to it.range.last + 1) }
            }.sortedBy { it.first }
            val boundary = candidates.firstOrNull { (_, after) ->
                nextRequest.containsMatchIn(remaining.substring(after).trimStart())
            }
            if (boundary == null) {
                results += remaining
                break
            }
            val (before, after) = boundary
            val left = remaining.substring(0, before).trim()
            if (left.any(Char::isLetterOrDigit)) results += left
            remaining = remaining.substring(after).trimStart()
                .replaceFirst(Regex("""(?i)^e\s+"""), "")
        }
        return results
    }
}

/** Backwards-compatible facade used by older callers and tests. */
object CompoundRequestSplitter {
    fun split(text: String, maxParts: Int = 6): List<String> =
        TurnPlanner.plan(text, maxParts).requests.map { it.text }
}

/**
 * Conservative fallback for selecting the larger local model.
 *
 * The fast model remains the primary classifier, but a malformed/empty
 * classifier reply must not force an obviously explanatory or comparative
 * question onto the 1B model.
 */
object ComplexityHeuristic {
    private val reasoningSignals = listOf(
        "perche", "come si ", "come posso", "in che modo", "spiega", "analizza",
        "confront", "differenz", "vantaggi", "svantaggi", "pro e contro",
        "quale conviene", "cosa serve", "di quali attrezz", "passo passo",
        "procedura", "consigli", "valuta", "stima", "indicativ",
        "quanto tempo richied", "quanto mi richied",
    )

    fun needsReasoning(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isBlank()) return false
        if (text.count { it == '?' } > 1 || ';' in text || '\n' in text) return true
        if (reasoningSignals.any(normalized::contains)) return true
        return normalized.split(Regex("""\s+""")).count { it.isNotBlank() } >= 22
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
}
