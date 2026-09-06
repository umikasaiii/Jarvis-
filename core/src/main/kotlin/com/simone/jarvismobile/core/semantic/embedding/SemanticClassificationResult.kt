package com.simone.jarvismobile.core.semantic.embedding

import com.simone.jarvismobile.core.semantic.SemanticFrame
import com.simone.jarvismobile.core.tools.ToolFamily

/**
 * § FASE 2A.11 §2 — the typed, confidence-aware output of one
 * [SemanticClassifier] call. [frame] is non-null only when the query was
 * confidently in-distribution; an out-of-distribution or unavailable result
 * NEVER fabricates a frame — see [ood]/[failureReason].
 */
data class SemanticClassificationResult(
    val frame: SemanticFrame?,
    val ood: Boolean,
    val intentScore: Float,
    val intentMargin: Float,
    val domainScores: Map<ToolFamily, Float>,
    val operationScore: Float,
    val referenceModeScore: Float,
    /** Non-null only when [frame] is null — e.g. `"ood:low_confidence"`, `"ood:low_margin"`, `"SEMANTIC_MODEL_UNAVAILABLE"`. Never the query text itself. */
    val failureReason: String? = null,
) {
    companion object {
        fun unavailable(reason: String = "SEMANTIC_MODEL_UNAVAILABLE") = SemanticClassificationResult(
            frame = null,
            ood = true,
            intentScore = 0f,
            intentMargin = 0f,
            domainScores = emptyMap(),
            operationScore = 0f,
            referenceModeScore = 0f,
            failureReason = reason,
        )
    }
}

/**
 * § FASE 2A.11 §2 — pure contract: "capire cosa significa la richiesta",
 * nothing else. An implementation MUST NOT execute a tool or author a side
 * effect — the exact same constraint [com.simone.jarvismobile.core.semantic.SemanticInterpreter]
 * already carries, and for the same reason: only [com.simone.jarvismobile.core.semantic.SemanticRouter]
 * (via the existing capability/tool-calling paths) is ever allowed to act.
 * [previousFrame] is the last frame that actually validated+merged — same
 * contract as [com.simone.jarvismobile.core.semantic.SemanticDialogueContext.previousFrame],
 * reused rather than inventing a second "conversation context" shape.
 */
interface SemanticClassifier {
    suspend fun classify(text: String, previousFrame: SemanticFrame?): SemanticClassificationResult
}
