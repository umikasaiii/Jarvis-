package com.simone.jarvismobile.core.memory

import kotlinx.serialization.Serializable

@Serializable
data class MemoryTurn(val fromUser: Boolean, val text: String)

/** App-private, conversation-scoped recap. It is never written to Obsidian. */
@Serializable
data class ShortTermMemorySnapshot(
    val facts: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val dates: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = facts.isEmpty() && topics.isEmpty() && people.isEmpty() && dates.isEmpty()

    fun forPrompt(): String = buildString {
        if (facts.isNotEmpty()) {
            append("Dichiarazioni dell'utente in questa conversazione (non verificate): ")
            append(facts.joinToString(" · "))
        }
        if (topics.isNotEmpty()) append("\nArgomenti: ").append(topics.joinToString(", "))
        if (people.isNotEmpty()) append("\nPersone: ").append(people.joinToString(", "))
        if (dates.isNotEmpty()) append("\nRiferimenti temporali: ").append(dates.joinToString(", "))
    }.trim()
}

/**
 * Rolling recap without an extra LLM pass. Only user statements old enough to
 * fall outside the immediate transcript are retained; questions, acknowledgments
 * and credentials are excluded. The previous snapshot is merged so a bounded
 * chat-file rotation does not silently erase older facts.
 */
object ShortTermMemorySummarizer {
    fun summarize(
        turns: List<MemoryTurn>,
        previous: ShortTermMemorySnapshot = ShortTermMemorySnapshot(),
        now: Long = System.currentTimeMillis(),
        recentTurnsKeptVerbatim: Int = 12,
        maxFacts: Int = 12,
    ): ShortTermMemorySnapshot {
        val older = turns.dropLast(recentTurnsKeptVerbatim.coerceAtLeast(0))
        val candidates = older.asSequence()
            .filter { it.fromUser }
            .map { normalize(it.text) }
            .filter { it.length in 8..360 }
            .filterNot { it in ACKNOWLEDGMENTS }
            .filterNot { it.endsWith('?') }
            // a question or a request phrased without "?", a supposition or a guess is not something the user STATED about themselves
            .filterNot { ASKING.containsMatchIn(com.simone.jarvismobile.core.knowledge.KnowledgeText.fold(it)) }
            .filterNot(MemoryAdmission::looksSpeculative)
            .filterNot(MemoryStructure::containsCredential)
            .toList()

        // a later correction REPLACES the earlier value of the same subject in this recap (the full history stays in the chat)
        val merged = ArrayList(previous.facts)
        for (c in candidates) {
            if (merged.any { it.equals(c, ignoreCase = true) }) continue
            val key = FactKey.parse(c)
            if (key != null && key.correction) merged.removeAll { FactKey.parse(it)?.key == key.key }
            merged += c
        }
        val facts = merged.takeLast(maxFacts.coerceAtLeast(1))
        val structure = MemoryStructure.extract(facts.joinToString(". "))
        return ShortTermMemorySnapshot(
            facts = facts,
            topics = structure.topics,
            people = structure.people,
            dates = structure.dates,
            updatedAt = now,
        )
    }

    private val ASKING = Regex("^(?:qual|quali|chi|quando|dove|quanto|quanti|quante|quanta|come|cosa|che cosa|perche|parlami|dimmi|spiegami|descrivi|elenca|traduci|riscrivi|calcola|cerca|mostra|puoi|potresti|riassumi|confronta)\\b")

    private fun normalize(value: String): String = value.replace(Regex("""\s+"""), " ").trim()

    private val ACKNOWLEDGMENTS = setOf(
        "ok", "okay", "va bene", "sì", "si", "no", "grazie", "perfetto", "capito", "continua", "riprendi",
    )
}
