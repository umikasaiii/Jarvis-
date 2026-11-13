package com.simone.jarvismobile.core.quality

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * HOTFIX 5 — builds the system instruction of a NEW conversation. Pure so the same code is measured by the
 * benchmark and shipped in the app.
 *
 * What changed against the previous in-app builder (measured in `PromptBudgetBenchmarkTest`):
 *  - the conversation recap is NOT pasted here any more: earlier turns are handed to the engine as real
 *    user/model turns ([com.simone.jarvismobile.core.llm.ChatSeed]), so the model is not asked to continue a
 *    transcript written inside its system block ("Simone: … / Tu: …" invited simulated dialogue);
 *  - notes and the short memory are capped by token budget instead of being appended unbounded;
 *  - the base instruction itself is the compact asset `prompts/jarvis_system_it.md`.
 */
object ClassicPromptComposer {
    private val DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN)

    fun compose(
        base: String,
        persona: String,
        assistantName: String,
        now: LocalDateTime,
        notes: List<String>,
        shortMemory: String,
        budget: ContextBudget = ContextBudget(),
    ): String = buildString {
        append(base.trim())
        persona.trim().takeIf { it.isNotEmpty() }?.let { append("\n\nPersonalità: ").append(it) }
        append("\n\nTi chiami ").append(assistantName).append('.')
        append("\nAdesso è ").append(now.format(DATE)).append('.')

        val cap = budget.notesTokens
        var used = 0
        val kept = ArrayList<String>()
        for (n in notes) {
            val e = TokenEstimator.estimate(n) + 2
            if (used + e > cap) break
            kept += n; used += e
        }
        if (kept.isNotEmpty()) {
            append("\n\nAppunti di Simone (usali se pertinenti, senza citarli se non serve):\n")
            kept.forEach { append("- ").append(it).append('\n') }
        }
        val mem = shortMemory.trim()
        if (mem.isNotEmpty()) {
            append("\nMemoria breve di questa conversazione (temporanea): ")
            append(PromptBudgeter.shorten(mem, SHORT_MEMORY_MAX_CHARS))
        }
    }.trimEnd()

    const val SHORT_MEMORY_MAX_CHARS = 600
}
