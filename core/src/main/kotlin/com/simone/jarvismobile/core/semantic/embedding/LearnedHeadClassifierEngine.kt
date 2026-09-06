package com.simone.jarvismobile.core.semantic.embedding

import com.simone.jarvismobile.core.semantic.ReferenceMode
import com.simone.jarvismobile.core.semantic.SemanticFrame
import com.simone.jarvismobile.core.semantic.SemanticIntent
import com.simone.jarvismobile.core.semantic.SemanticOperation
import com.simone.jarvismobile.core.semantic.SemanticSlot
import com.simone.jarvismobile.core.tools.ToolFamily

/**
 * § FASE 2A.11 ADDENDUM §11 — the LEARNED counterpart to
 * [PrototypeSemanticClassifierEngine]: same public shape
 * (`classify(queryEmbedding, previousFrame): SemanticClassificationResult`),
 * same architectural invariants (referenceMode resolved BEFORE domains so a
 * bare follow-up never asserts its own domain; KNOWLEDGE_QUERY always forces
 * `domains={KNOWLEDGE}`; CONVERSATION/CLARIFICATION/UNKNOWN never assert a
 * domain; [previousFrame] accepted for interface parity but never used to
 * bias the CURRENT turn's classification, §9) — the two are deliberately
 * interchangeable from `EmbeddingSemanticClassifier`'s point of view (§4:
 * "Design interface so a future trained classification head can swap in
 * without changing ConversationalJarvisEngine").
 *
 * The only real difference is WHERE the score for each label comes from:
 * [PrototypeSemanticClassifierEngine] computes a cosine similarity against a
 * centroid; this class runs [HeadMath.forward] (a trained linear/MLP head)
 * and reads a softmax (single-label heads) or per-label sigmoid (domain,
 * multi-label) over its logits — same [ClassifierThresholds], same OOD-gate
 * semantics (§6: low confidence OR low margin -> [SemanticClassificationResult.unavailable]-shaped
 * OOD result, NEVER a forced label).
 */
class LearnedHeadClassifierEngine(
    private val weights: LearnedHeadExport,
    private val thresholds: ClassifierThresholds = ClassifierThresholds.CONSERVATIVE_DEFAULT,
) {

    fun classify(queryEmbedding: EmbeddingVector, previousFrame: SemanticFrame? = null): SemanticClassificationResult {
        val intentLogits = HeadMath.forward(weights.intent, queryEmbedding)
        val intentProbs = HeadMath.softmax(intentLogits)
        val order = intentProbs.indices.sortedByDescending { intentProbs[it] }
        if (order.isEmpty()) {
            return SemanticClassificationResult.unavailable("LEARNED_HEAD_NO_INTENT_LABELS")
        }

        val bestIdx = order[0]
        val bestScore = intentProbs[bestIdx]
        val secondScore = if (order.size > 1) intentProbs[order[1]] else 0f
        val margin = bestScore - secondScore
        val intent = weights.intent.labels.getOrNull(bestIdx)?.let { runCatching { SemanticIntent.valueOf(it) }.getOrNull() }

        val ood = intent == null || bestScore < thresholds.intentConfidenceMin || margin < thresholds.intentMarginMin
        if (ood) {
            return SemanticClassificationResult(
                frame = null,
                ood = true,
                intentScore = bestScore,
                intentMargin = margin,
                domainScores = emptyMap(),
                operationScore = 0f,
                referenceModeScore = 0f,
                failureReason = when {
                    intent == null -> "ood:unknown_label:${weights.intent.labels.getOrNull(bestIdx)}"
                    bestScore < thresholds.intentConfidenceMin -> "ood:low_confidence:$bestScore"
                    else -> "ood:low_margin:$margin"
                },
            )
        }
        checkNotNull(intent)

        // § referenceMode resolved BEFORE domains — same reasoning as
        // PrototypeSemanticClassifierEngine's own comment on this ordering.
        val (referenceMode, referenceModeScore) = resolveReferenceMode(queryEmbedding)

        val domainLogits = HeadMath.forward(weights.domain, queryEmbedding)
        val domainScores: Map<ToolFamily, Float> = weights.domain.labels.indices.mapNotNull { i ->
            val family = runCatching { ToolFamily.valueOf(weights.domain.labels[i]) }.getOrNull() ?: return@mapNotNull null
            family to HeadMath.sigmoid(domainLogits[i])
        }.toMap()

        val domains: Set<ToolFamily> = when {
            intent == SemanticIntent.KNOWLEDGE_QUERY -> setOf(ToolFamily.KNOWLEDGE)
            intent == SemanticIntent.CONVERSATION || intent == SemanticIntent.CLARIFICATION || intent == SemanticIntent.UNKNOWN ->
                emptySet()
            referenceMode != ReferenceMode.NONE -> emptySet()
            else -> domainScores.filterValues { it >= thresholds.domainSimilarityMin }.keys
        }

        val (operation, operationScore) = resolveOperation(queryEmbedding)

        val explicitSlots = buildSet {
            if (domains.isNotEmpty()) add(SemanticSlot.DOMAINS)
            if (operation != SemanticOperation.UNKNOWN) add(SemanticSlot.OPERATION)
        }

        // § temporalExpression stays null — same reasoning as
        // PrototypeSemanticClassifierEngine: no token-level extraction head
        // exists (only a sentence embedding), and the real call sites
        // (weatherCall/healthCall/agendaCallFromFrame) already re-derive the
        // date from the raw transcript, never from this field.
        val frame = SemanticFrame(
            intent = intent,
            domains = domains,
            operation = operation,
            temporalExpression = null,
            metric = null,
            aggregation = null,
            entities = emptyList(),
            referenceMode = referenceMode,
            requiresGrounding = domains.isNotEmpty(),
            confidence = bestScore.toDouble(),
            explicitSlots = explicitSlots,
        )

        return SemanticClassificationResult(
            frame = frame,
            ood = false,
            intentScore = bestScore,
            intentMargin = margin,
            domainScores = domainScores,
            operationScore = operationScore,
            referenceModeScore = referenceModeScore,
        )
    }

    private fun resolveReferenceMode(queryEmbedding: EmbeddingVector): Pair<ReferenceMode, Float> {
        val head = weights.referenceMode ?: return ReferenceMode.NONE to 0f
        val probs = HeadMath.softmax(HeadMath.forward(head, queryEmbedding))
        val idx = probs.indices.maxByOrNull { probs[it] } ?: return ReferenceMode.NONE to 0f
        val score = probs[idx]
        val mode = head.labels.getOrNull(idx)?.let { runCatching { ReferenceMode.valueOf(it) }.getOrNull() }
        return if (mode != null && score >= thresholds.referenceModeConfidenceMin) mode to score else ReferenceMode.NONE to score
    }

    private fun resolveOperation(queryEmbedding: EmbeddingVector): Pair<SemanticOperation, Float> {
        val head = weights.operation ?: return SemanticOperation.UNKNOWN to 0f
        val probs = HeadMath.softmax(HeadMath.forward(head, queryEmbedding))
        val idx = probs.indices.maxByOrNull { probs[it] } ?: return SemanticOperation.UNKNOWN to 0f
        val score = probs[idx]
        val op = head.labels.getOrNull(idx)?.let { runCatching { SemanticOperation.valueOf(it) }.getOrNull() }
        return if (op != null && score >= thresholds.operationConfidenceMin) op to score else SemanticOperation.UNKNOWN to score
    }
}
