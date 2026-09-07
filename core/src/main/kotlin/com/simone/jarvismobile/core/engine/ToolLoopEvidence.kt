package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus
import com.simone.jarvismobile.core.tools.ToolOutcomeTier
import com.simone.jarvismobile.core.tools.tier

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 2 (Structured Grounding +
 * Internal/User Presentation Boundary), §2 "evidence must survive the tool
 * loop" (finding JARVIS-05). Pure, testable core of what
 * `ConversationalJarvisEngine.runBrainLoop`'s per-round continuation used to
 * get wrong: round 2+ replaced BOTH the turn's original question AND every
 * earlier round's tool results with only the LATEST round's spoken text —
 * `contextBlock` was cleared to `""` and `currentText` became just
 * `"Risultato degli strumenti eseguiti:\n<this round only>"`, so a model that
 * needed two rounds could never see what an earlier round found, nor be
 * reminded what Simone actually asked. Extracted here as pure functions so
 * these invariants are real, running tests instead of something reviewable by
 * eye only — this project has no Robolectric/instrumented infra to exercise
 * the Android engine class directly (see [GroundingGate]'s own doc comment
 * for the same reasoning).
 */
object ToolLoopEvidence {

    /**
     * One tool's real outcome from one round of the loop. [status] is
     * whatever [ToolOutcomeStatus] the outcome actually carries — for a tool
     * without [com.simone.jarvismobile.core.tools.StructuredToolResult] yet,
     * callers pass the same honest legacy fallback
     * [com.simone.jarvismobile.core.tools.resolveOutcomeStatus] already
     * produces, never `null` for a real execution (only a
     * `NeedsConfirmation` outcome, which never reaches this accumulator,
     * has no status at all). [spoken] is never a raw payload — the same
     * human-facing sentence already shown/spoken elsewhere, reused as the
     * only thing carried into the model's own reasoning context.
     */
    data class RoundResult(val toolName: String, val status: ToolOutcomeStatus?, val spoken: String)

    /**
     * Builds the text handed to the model for the NEXT round. Includes the
     * ORIGINAL question verbatim (never replaced by a later round's
     * synthetic follow-up), and every tool result accumulated so far across
     * ALL rounds — never only the latest — so an earlier round's finding
     * survives even if a later round's tool call fails or targets something
     * unrelated (§ PARTIAL, "do not hide the successful portion").
     */
    fun buildContinuation(originalQuestion: String, accumulated: List<RoundResult>): String {
        val resultsBlock = accumulated.joinToString("\n") { "- ${it.toolName}: ${it.spoken}" }
        return "Domanda originale di Simone: \"$originalQuestion\"\n\n" +
            "Risultati reali degli strumenti eseguiti finora:\n$resultsBlock\n\n" +
            "Se serve un altro strumento richiedilo in tool_calls, altrimenti componi ora la risposta finale " +
            "per Simone in assistant_text, in linguaggio naturale, rispondendo alla domanda originale sopra " +
            "(non ignorarla), e lascia tool_calls vuoto."
    }

    /**
     * § PASSAGGIO 2 §4 — "SUCCESS_EMPTY → deterministic honest empty message
     * or equivalent controlled path": when EVERY result accumulated so far is
     * a confidently-known [ToolOutcomeStatus.SUCCESS_EMPTY] (never a legacy/
     * unknown-fidelity result — `status == null` never occurs here per
     * [RoundResult]'s own contract, but a mixed/legacy caller could still
     * pass one defensively — and never mixed with real data), the turn's
     * answer is exactly the tool(s)' own already-deterministic [RoundResult.spoken]
     * text: no further model round is needed, so a small FAST model can never
     * paraphrase "no data" into something that sounds like it found data.
     * Returns null for every other mix (including a single non-empty or
     * unknown-status result) — meaning: fall through to the existing
     * model-synthesis round, unchanged. Deliberately narrow, not a general
     * "sometimes skip the LLM" mechanism.
     */
    fun deterministicEmptyPresentationOrNull(results: List<RoundResult>): String? {
        if (results.isEmpty()) return null
        if (results.any { it.status != ToolOutcomeStatus.SUCCESS_EMPTY }) return null
        return results.joinToString("\n") { it.spoken }.trim().ifBlank { null }
    }

    /**
     * § PASSAGGIO 2 §6 — "a model failure after successful evidence
     * collection must not erase the evidence": when the model itself becomes
     * unavailable mid-loop (a real local-engine failure, never a grounding
     * decision), any tool evidence already collected this turn that is
     * actually usable ([ToolOutcomeTier.NORMAL]/[ToolOutcomeTier.DEGRADED] —
     * real or genuinely-empty data, or a partial/stale-but-carried result,
     * never a bare failure) is still presented via each tool's own
     * already-deterministic [RoundResult.spoken] text, instead of discarding
     * it for a generic error the user has no way to distinguish from
     * "nothing was ever found out". Returns null when there is nothing
     * presentable (no results yet, or every result is itself an
     * [ToolOutcomeTier.UNAVAILABLE] failure) — callers fall back to the
     * existing generic error in that case.
     */
    fun presentAccumulatedOrNull(results: List<RoundResult>): String? {
        val presentable = results.filter { it.status != null && it.status.tier() != ToolOutcomeTier.UNAVAILABLE }
        if (presentable.isEmpty()) return null
        return presentable.joinToString("\n") { it.spoken }.trim().ifBlank { null }
    }
}
