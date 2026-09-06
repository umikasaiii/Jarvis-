package com.simone.jarvismobile.core.semantic.embedding

import com.simone.jarvismobile.core.semantic.ReferenceMode
import com.simone.jarvismobile.core.semantic.SemanticFrame
import com.simone.jarvismobile.core.semantic.SemanticIntent
import com.simone.jarvismobile.core.semantic.SemanticOperation
import com.simone.jarvismobile.core.semantic.SemanticSlot
import com.simone.jarvismobile.core.tools.ToolFamily

/**
 * § FASE 2A.11 §4 — the pure classification ALGORITHM: given an already-
 * computed query [EmbeddingVector] and a corpus of already-embedded
 * [SemanticPrototype]s, produce a [SemanticClassificationResult]. This is the
 * class the spec's "prototype classifier" §4 and future "learned head" §13
 * both implement the SAME way from [ConversationalJarvisEngine][com.simone.jarvismobile.engine.ConversationalJarvisEngine]'s
 * point of view — neither is reachable from here at all; the app-level
 * `EmbeddingSemanticClassifier` is the only thing that calls [classify], after
 * running the real text through `SemanticEmbeddingEngine.embed()`.
 *
 * **Why this is testable without the real EmbeddingGemma model**: an
 * embedding is just a [FloatArray] to every function in this file — real or
 * synthetic, cosine similarity/centroid math behaves identically. This
 * project has no network access to fetch EmbeddingGemma's weights and no
 * Android runtime to execute a `.tflite` graph in this environment (see
 * `CLAUDE.md`'s "Environment note"), so every test in
 * `PrototypeSemanticClassifierEngineTest` drives this class with a
 * deterministic FAKE embedder instead — proving the ALGORITHM's invariants
 * (cross-domain non-contamination, multi-label domains, OOD gating, no
 * cross-turn state) at scale, exactly as `SemanticFrameMerger`/`SemanticRouter`
 * were already proven against hand-built frames instead of real LLM output in
 * FASE 2A.9/2A.10. The real embedding VALUES are only ever produced on a real
 * device — this class cannot tell the difference, which is the whole point.
 */
class PrototypeSemanticClassifierEngine(
    embeddedPrototypes: List<EmbeddedPrototype>,
    private val thresholds: ClassifierThresholds = ClassifierThresholds.CONSERVATIVE_DEFAULT,
) {
    /** One prototype paired with its (real or synthetic) embedding. */
    data class EmbeddedPrototype(val prototype: SemanticPrototype, val embedding: EmbeddingVector)

    private val intentClassifier = SingleLabelCentroidClassifier(
        embeddedPrototypes.groupBy { it.prototype.intent }.mapValues { (_, eps) -> eps.map { it.embedding } },
    )

    /**
     * § FASE 2A.11 §8 — KNOWLEDGE is deliberately EXCLUDED from the general
     * multi-label domain pool: it is never decided by domain-centroid
     * similarity at all, only by the intent branch below (a
     * [SemanticIntent.KNOWLEDGE_QUERY] frame's domains are always forced to
     * exactly `{KNOWLEDGE}`) — the same coherence rule
     * `SemanticFrameValidator` already enforces downstream, reached here by
     * construction instead of by chance.
     */
    private val domainClassifier = MultiLabelCentroidClassifier(
        embeddedPrototypes
            .flatMap { ep -> ep.prototype.domains.filter { it != ToolFamily.KNOWLEDGE }.map { domain -> domain to ep.embedding } }
            .groupBy({ it.first }, { it.second }),
    )

    private val operationClassifier = SingleLabelCentroidClassifier(
        embeddedPrototypes
            .filter { it.prototype.operation != SemanticOperation.UNKNOWN }
            .groupBy { it.prototype.operation }
            .mapValues { (_, eps) -> eps.map { it.embedding } },
    )

    private val referenceModeClassifier = SingleLabelCentroidClassifier(
        embeddedPrototypes.groupBy { it.prototype.referenceMode }.mapValues { (_, eps) -> eps.map { it.embedding } },
    )

    /**
     * [previousFrame] is accepted for interface parity with [SemanticClassifier]
     * but deliberately NOT used to bias this turn's own classification (§9:
     * "Il classificatore classifica innanzitutto il turno corrente" — letting
     * the previous frame nudge the CURRENT decision is exactly the class of
     * bug FASE 2A.9/2A.10 exist to close). Inheriting slots from
     * [previousFrame] remains entirely `SemanticFrameMerger`'s job, one layer
     * up, unchanged.
     */
    fun classify(queryEmbedding: EmbeddingVector, previousFrame: SemanticFrame? = null): SemanticClassificationResult {
        val intentResult = intentClassifier.classify(queryEmbedding)
        val intent = intentResult.best
        val ood = intent == null ||
            intentResult.bestScore < thresholds.intentConfidenceMin ||
            intentResult.margin < thresholds.intentMarginMin

        if (ood) {
            return SemanticClassificationResult(
                frame = null,
                ood = true,
                intentScore = intentResult.bestScore,
                intentMargin = intentResult.margin,
                domainScores = domainClassifier.scoreAll(queryEmbedding),
                operationScore = 0f,
                referenceModeScore = 0f,
                failureReason = if (intentResult.bestScore < thresholds.intentConfidenceMin) {
                    "ood:low_confidence:${intentResult.bestScore}"
                } else {
                    "ood:low_margin:${intentResult.margin}"
                },
            )
        }
        checkNotNull(intent)

        // § classified BEFORE domains, deliberately: a turn whose reference
        // mode is ELLIPSIS/PARTITIVE is, by definition (§10), one that names
        // no domain of its own — its whole content is "the same thing as
        // before, with one slot changed". Gating domains on this (below)
        // instead of trusting the domain centroid scan for such a turn
        // avoids a real failure mode: a short follow-up's few remaining
        // words (mostly a bare temporal/partitive reference) can coincide
        // weakly with several domains' centroids at once without genuinely
        // asserting any of them — inheritance is `SemanticFrameMerger`'s job
        // one layer up, never this classifier's.
        val referenceModeResult = referenceModeClassifier.classify(queryEmbedding)
        val referenceMode = if (referenceModeResult.bestScore >= thresholds.referenceModeConfidenceMin) {
            referenceModeResult.best ?: ReferenceMode.NONE
        } else {
            ReferenceMode.NONE
        }

        val domainScores = domainClassifier.scoreAll(queryEmbedding)
        val domains: Set<ToolFamily> = when {
            // § FASE 2A.11 §8 hard negative — forced, never scored: a
            // KNOWLEDGE_QUERY's domain is always exactly KNOWLEDGE, decided
            // by WHICH INTENT won, not by a second similarity check that
            // could disagree with it.
            intent == SemanticIntent.KNOWLEDGE_QUERY -> setOf(ToolFamily.KNOWLEDGE)
            // A bare conversational/clarifying turn never implies a capability domain.
            intent == SemanticIntent.CONVERSATION || intent == SemanticIntent.CLARIFICATION || intent == SemanticIntent.UNKNOWN ->
                emptySet()
            // § FASE 2A.11 §10 — see the comment above referenceMode.
            referenceMode != ReferenceMode.NONE -> emptySet()
            else -> domainScores.filterValues { it >= thresholds.domainSimilarityMin }.keys
        }

        val operationResult = operationClassifier.classify(queryEmbedding)
        val operation = if (operationResult.bestScore >= thresholds.operationConfidenceMin) {
            operationResult.best ?: SemanticOperation.UNKNOWN
        } else {
            SemanticOperation.UNKNOWN
        }

        val explicitSlots = buildSet {
            if (domains.isNotEmpty()) add(SemanticSlot.DOMAINS)
            if (operation != SemanticOperation.UNKNOWN) add(SemanticSlot.OPERATION)
        }

        // § FASE 2A.11 §11 — temporalExpression stays null, deliberately:
        // this classifier has no token-level extraction head (only a single
        // sentence embedding), so it never invents a hand-picked date span.
        // Verified against the real call sites this would otherwise need to
        // feed — `ConversationalJarvisEngine.weatherCall`/`healthCall` parse
        // the RAW TRANSCRIPT directly via `ItalianDateTimeParser`/
        // `HealthQueryParser` and never read `frame.temporalExpression` at
        // all; `agendaCallFromFrame`'s one reader of it already falls back to
        // `TemporalScopeResolver.resolve(transcript, now)` whenever it is
        // null. So leaving it null costs nothing here — the existing
        // deterministic parsers already re-derive the real date from the raw
        // text every turn, exactly as the spec requires (§11: "NON deve
        // essere responsabile del calcolo preciso delle date").
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
            confidence = intentResult.bestScore.toDouble(),
            explicitSlots = explicitSlots,
        )

        return SemanticClassificationResult(
            frame = frame,
            ood = false,
            intentScore = intentResult.bestScore,
            intentMargin = intentResult.margin,
            domainScores = domainScores,
            operationScore = operationResult.bestScore,
            referenceModeScore = referenceModeResult.bestScore,
        )
    }
}
