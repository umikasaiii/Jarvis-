package com.simone.jarvismobile.core.semantic

/**
 * § FASE 2A.9 §12 — validity depends on MORE than the model's own reported
 * confidence: parsable output (already enforced by [SemanticOutputParser]),
 * valid enums (ditto), PLUS the semantic coherence checks below. A frame
 * that fails any of these is never routed — it becomes
 * [SemanticInterpretation.Invalid], the same safe-failure outcome as an
 * unparsable line.
 */
object SemanticFrameValidator {

    /** Below this, "uncertain" wins over "maybe right" (§12: prefer clarification/LLM loop over the wrong tool). */
    const val MIN_CONFIDENCE = 0.35

    fun validate(frame: SemanticFrame): SemanticInterpretation {
        if (frame.confidence < MIN_CONFIDENCE) {
            return SemanticInterpretation.Invalid("low_confidence:${frame.confidence}")
        }
        when (frame.intent) {
            SemanticIntent.CAPABILITY_QUERY -> {
                if (frame.domains.isEmpty() && frame.referenceMode == ReferenceMode.NONE) {
                    // A self-sufficient capability query with no domain and no
                    // reference to resolve against is not routable at all.
                    return SemanticInterpretation.Invalid("capability_query_no_domain")
                }
            }
            SemanticIntent.KNOWLEDGE_QUERY -> {
                val incoherentDomain = frame.domains.any { it != com.simone.jarvismobile.core.tools.ToolFamily.KNOWLEDGE }
                if (incoherentDomain) {
                    // § FASE 2A.9 §8 — KNOWLEDGE_QUERY must never carry a
                    // grounded-data domain (that is DEVICE_INFO's job, a
                    // different intent entirely for the same surface words).
                    return SemanticInterpretation.Invalid("knowledge_query_grounded_domain")
                }
            }
            SemanticIntent.MULTI_SOURCE_REASONING -> {
                if (frame.domains.size < 2) {
                    return SemanticInterpretation.Invalid("multi_source_needs_2_domains:${frame.domains.size}")
                }
            }
            else -> Unit
        }
        return SemanticInterpretation.Valid(frame)
    }
}
