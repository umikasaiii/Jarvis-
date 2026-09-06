package com.simone.jarvismobile.core.semantic.embedding

import com.simone.jarvismobile.core.semantic.SemanticDialogueContext
import com.simone.jarvismobile.core.semantic.SemanticInterpretation
import com.simone.jarvismobile.core.semantic.SemanticInterpreter

/**
 * § FASE 2A.11 §16 — the ENTIRE integration surface between the new
 * embedding classifier and the existing FASE 2A.9/2A.10 pipeline:
 * `ConversationalJarvisEngine.runSemanticPath`'s retry-once logic,
 * `SemanticFrameValidator`, `SemanticFrameMerger` and `SemanticRouter` are
 * all reused byte-for-byte unchanged — this class is the ONLY new thing
 * `ConversationalJarvisEngine` ever sees, wired in by `SemanticModule`
 * (`app/`) in place of the old [SemanticInterpreter] binding. No second
 * router, no parallel pipeline (§22).
 *
 * Maps [SemanticClassificationResult] to [SemanticInterpretation] the same
 * way the old text-generation path already did: an out-of-distribution or
 * unavailable result becomes [SemanticInterpretation.Invalid] — which,
 * critically, is EXACTLY the outcome that already routes to
 * `SemanticRouteResult.LegacyFallback` → `runBrainLoop` after FASE 2A.10
 * deleted the keyword/topic fallback paths — so "OOD → HandoffToLlm, never
 * legacy keyword routing" (§6) falls out of the EXISTING type system for
 * free, not from new code.
 */
class SemanticClassifierInterpreterAdapter(
    private val classifier: SemanticClassifier,
) : SemanticInterpreter, HasSemanticTiming {
    override suspend fun interpret(text: String, dialogueContext: SemanticDialogueContext): SemanticInterpretation {
        val result = classifier.classify(text, dialogueContext.previousFrame)
        return if (result.ood || result.frame == null) {
            SemanticInterpretation.Invalid(result.failureReason ?: "ood:unspecified")
        } else {
            SemanticInterpretation.Valid(result.frame)
        }
    }

    /** Delegates to [classifier] when it tracks its own timing (the real `EmbeddingSemanticClassifier` does); null otherwise. */
    override fun lastSemanticTiming(): SemanticInterpreterTiming? = (classifier as? HasSemanticTiming)?.lastSemanticTiming()
}
