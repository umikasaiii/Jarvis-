package com.simone.jarvismobile.core.semantic.embedding

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * § FASE 2A.11 §6 — "Le soglie NON vanno inventate a caso nel codice. Crea un
 * validation/calibration corpus e ricava soglie conservative." These
 * defaults ARE hand-picked, not derived from a real EmbeddingGemma
 * calibration run — this environment cannot run the real model at all (no
 * network access to fetch it, no Android runtime to execute it — see the
 * package-level honesty note) — so they are deliberately CONSERVATIVE
 * (favoring [PrototypeSemanticClassifierEngine] handing off to the LLM loop
 * over a wrong grounded answer, exactly the spec's own tie-breaker: "Una
 * falsa classificazione grounded è peggiore di un handoff"), not calibrated.
 * `tools/semantic_classifier/calibrate.py` computes real thresholds from a
 * genuine validation split once run against the real model on a machine that
 * has it — its output is this exact same JSON shape, loaded at runtime
 * instead of these defaults, no code change required to adopt calibrated
 * values.
 */
@Serializable
data class ClassifierThresholds(
    /** Below this cosine similarity, the winning intent is not trusted at all. */
    val intentConfidenceMin: Float = 0.45f,
    /** Below this top-1/top-2 margin, two intents are too close to call — ambiguous, not confidently either one. */
    val intentMarginMin: Float = 0.05f,
    /** A domain's cosine similarity must clear this to be included in a multi-label result. */
    val domainSimilarityMin: Float = 0.40f,
    /** Below this, [SemanticOperation] stays [com.simone.jarvismobile.core.semantic.SemanticOperation.UNKNOWN] — the existing safe default `SemanticRouter` already treats as read-only-compatible. */
    val operationConfidenceMin: Float = 0.40f,
    /** Below this, [com.simone.jarvismobile.core.semantic.ReferenceMode] stays `NONE` — the safest default (never wrongly triggers ellipsis/partitive inheritance). */
    val referenceModeConfidenceMin: Float = 0.40f,
) {
    companion object {
        val CONSERVATIVE_DEFAULT = ClassifierThresholds()
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(source: String): ClassifierThresholds = json.decodeFromString(serializer(), source)
    }
}
