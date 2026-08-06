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
            append("Fatti/decisioni: ")
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
            .filterNot(MemoryStructure::containsCredential)
            .toList()

        val facts = (previous.facts + candidates)
            .distinctBy { it.lowercase() }
            .takeLast(maxFacts.coerceAtLeast(1))
        val structure = MemoryStructure.extract(facts.joinToString(". "))
        return ShortTermMemorySnapshot(
            facts = facts,
            topics = structure.topics,
            people = structure.people,
            dates = structure.dates,
            updatedAt = now,
        )
    }

    private fun normalize(value: String): String = value.replace(Regex("""\s+"""), " ").trim()

    private val ACKNOWLEDGMENTS = setOf(
        "ok", "okay", "va bene", "sì", "si", "no", "grazie", "perfetto", "capito", "continua", "riprendi",
    )
}
