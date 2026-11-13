package com.simone.jarvismobile.core.quality

import com.simone.jarvismobile.core.llm.SeedTurn

/**
 * HOTFIX 5 — explicit allocation of the model's context window (LiteRT-LM `maxNumTokens`, input + output
 * together). Before this the prompt was whatever the pieces added up to: a 3.3 KB instruction, a persona, a
 * 16-message recap of up to 320 characters each and retrieved notes could consume most of 4096 tokens BEFORE the
 * first question, leaving the reply the leftovers. Every figure here is an estimate (see [TokenEstimator]).
 */
data class ContextBudget(
    val windowTokens: Int = 4096,
    /** Space always kept free for the model's reply (never given to the prompt). */
    val replyReserveTokens: Int = 1024,
    val systemTokens: Int = 700,
    val historyTokens: Int = 700,
    val notesTokens: Int = 600,
    /** One assistant turn replayed as history is cut (at a sentence boundary) beyond this. */
    val historyTurnMaxChars: Int = 420,
) {
    /** Tokens available to everything that is sent before/with the question. */
    val promptCeiling: Int get() = windowTokens - replyReserveTokens
}

data class PromptParts(
    val system: String,
    val notes: List<String>,
    val history: List<SeedTurn>,
    val estSystem: Int,
    val estNotes: Int,
    val estHistory: Int,
    val estUser: Int,
    val droppedNotes: Int,
    val droppedHistoryTurns: Int,
    val userExceedsBudget: Boolean,
) {
    val estTotal: Int get() = estSystem + estNotes + estHistory + estUser
}

object PromptBudgeter {
    /**
     * Fits [history] and [notes] around the (never truncated) [user] message. Order of priority:
     * current question > system instruction > newest history turns > notes. History keeps the NEWEST turns,
     * whole, and shortens a long assistant turn at a sentence boundary instead of mid-word.
     */
    fun plan(
        system: String,
        notes: List<String>,
        history: List<SeedTurn>,
        user: String,
        budget: ContextBudget = ContextBudget(),
    ): PromptParts {
        val estUser = TokenEstimator.message(user)
        val estSystem = TokenEstimator.message(system)
        // The system text is built by the caller under its own cap; if it is over its share it is still sent
        // whole (cutting instructions silently is worse), and the overrun is visible in the diagnostics.
        var room = budget.promptCeiling - estUser - estSystem

        val keptNotes = ArrayList<String>()
        var estNotes = 0
        val notesCap = minOf(budget.notesTokens, maxOf(room, 0))
        for (n in notes) {
            val e = TokenEstimator.estimate(n) + 2
            if (estNotes + e > notesCap) break
            keptNotes += n; estNotes += e
        }
        room -= estNotes

        val historyCap = minOf(budget.historyTokens, maxOf(room, 0))
        val normalized = history.map { it.copy(text = shorten(it.text, budget.historyTurnMaxChars)) }
        val kept = ArrayDeque<SeedTurn>()
        var estHistory = 0
        for (turn in normalized.asReversed()) {
            val e = TokenEstimator.message(turn.text)
            if (estHistory + e > historyCap) break
            kept.addFirst(turn); estHistory += e
        }
        return PromptParts(
            system = system,
            notes = keptNotes,
            history = kept.toList(),
            estSystem = estSystem,
            estNotes = estNotes,
            estHistory = estHistory,
            estUser = estUser,
            droppedNotes = notes.size - keptNotes.size,
            droppedHistoryTurns = normalized.size - kept.size,
            userExceedsBudget = estUser + estSystem > budget.promptCeiling,
        )
    }

    /** Cuts [text] to at most [max] chars at the last sentence end (or word) before the limit; never mid-word. */
    fun shorten(text: String, max: Int): String {
        val t = text.trim()
        if (t.length <= max) return t
        val window = t.substring(0, max)
        val sentenceEnd = maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "))
        if (sentenceEnd >= max / 2) return window.substring(0, sentenceEnd + 1)
        val space = window.lastIndexOf(' ')
        return (if (space > 0) window.substring(0, space) else window).trimEnd(',', ';', ':') + "…"
    }
}

/**
 * HOTFIX 5 — running estimate of what the LIVE native conversation already holds. The native KV cache grows with
 * every turn; the app never knew how close it was to the window. When the next turn would eat into the reply
 * reserve, the caller rotates the conversation (reseeds it from a compact history) BEFORE the engine is forced to
 * cope with an overflow whose behaviour the API does not document.
 */
class ConversationTokenLedger(private val budget: ContextBudget = ContextBudget()) {
    var used: Int = 0
        private set

    fun start(seedTokens: Int) { used = seedTokens }

    fun reset() { used = 0 }

    fun record(userTokens: Int, replyTokens: Int) { used += userTokens + replyTokens }

    /** True when adding [nextUserTokens] would leave less than the reply reserve in the window. */
    fun needsRotation(nextUserTokens: Int): Boolean =
        used > 0 && used + nextUserTokens > budget.promptCeiling
}
