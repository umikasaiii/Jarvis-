package com.simone.jarvismobile.core.semantic.embedding

import com.simone.jarvismobile.core.semantic.ReferenceMode
import com.simone.jarvismobile.core.semantic.SemanticIntent
import com.simone.jarvismobile.core.semantic.SemanticOperation
import com.simone.jarvismobile.core.tools.ToolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * § FASE 2A.11 ADDENDUM §11 — proves [LearnedHeadClassifierEngine] upholds
 * the exact same architectural invariants as
 * [PrototypeSemanticClassifierEngine] (see that class's own test suite for
 * the centroid-based equivalents), using small HAND-BUILT linear/MLP weight
 * matrices — never a real trained head (no real EmbeddingGemma embeddings
 * exist in this environment, see `tools/semantic_classifier/README.md`).
 * This proves the MATH and the CONTROL FLOW are correct, not that a real
 * trained head would classify real sentences well.
 */
class LearnedHeadClassifierEngineTest {

    /** Three-label intent head: each label's weight row isolates one input dimension. */
    private fun intentHead(): ExportedHead = ExportedHead(
        architecture = "linear",
        labels = listOf("CAPABILITY_QUERY", "KNOWLEDGE_QUERY", "CONVERSATION"),
        linear = LinearWeights(
            weight = listOf(listOf(1f, 0f, 0f, 0f), listOf(0f, 1f, 0f, 0f), listOf(0f, 0f, 1f, 0f)),
            bias = listOf(0f, 0f, 0f),
        ),
    )

    private fun domainHead(): ExportedHead = ExportedHead(
        architecture = "linear",
        labels = listOf("WEATHER", "AGENDA"),
        linear = LinearWeights(
            // strongly positive bias so WEATHER/AGENDA both score high sigmoid
            // regardless of the (irrelevant, zero-weight) embedding — used to
            // prove intent-forced domain rules override a high domain score.
            weight = listOf(listOf(0f, 0f, 0f, 0f), listOf(0f, 0f, 0f, 0f)),
            bias = listOf(10f, 10f),
        ),
    )

    private fun referenceModeHead(): ExportedHead = ExportedHead(
        architecture = "linear",
        labels = listOf("NONE", "PARTITIVE", "ELLIPSIS"),
        linear = LinearWeights(
            weight = listOf(listOf(0f, 0f, 0f, 0f), listOf(0f, 0f, 0f, 1f), listOf(0f, 0f, 0f, -1f)),
            bias = listOf(0f, 0f, 0f),
        ),
    )

    private fun operationHead(): ExportedHead = ExportedHead(
        architecture = "linear",
        labels = listOf("CREATE", "DELETE"),
        linear = LinearWeights(
            weight = listOf(listOf(0f, 0f, 0f, 1f), listOf(0f, 0f, 0f, -1f)),
            bias = listOf(0f, 0f),
        ),
    )

    private fun export(includeReferenceMode: Boolean = true, includeOperation: Boolean = true) =
        LearnedHeadExport(
            schemaVersion = 1,
            embeddingDim = 4,
            intent = intentHead(),
            domain = domainHead(),
            operation = if (includeOperation) operationHead() else null,
            referenceMode = if (includeReferenceMode) referenceModeHead() else null,
        )

    private fun engine(thresholds: ClassifierThresholds = ClassifierThresholds.CONSERVATIVE_DEFAULT) =
        LearnedHeadClassifierEngine(export(), thresholds)

    @Test
    fun `confident unambiguous query resolves to the matching intent`() {
        val result = engine().classify(floatArrayOf(6f, 0f, 0f, 0f))
        assertEquals(false, result.ood)
        assertEquals(SemanticIntent.CAPABILITY_QUERY, result.frame?.intent)
    }

    @Test
    fun `near-uniform logits are rejected as OOD by low margin, never forced into a label`() {
        val result = engine().classify(floatArrayOf(0f, 0f, 0f, 0f))
        assertEquals(true, result.ood)
        assertEquals(null, result.frame)
        assertTrue(result.failureReason?.startsWith("ood:") == true)
    }

    @Test
    fun `knowledge query forces domains to exactly KNOWLEDGE, never the domain head score`() {
        // domainHead() would score WEATHER/AGENDA very high (bias=10) for ANY
        // embedding — proving the intent-forced rule wins regardless.
        val result = engine().classify(floatArrayOf(0f, 6f, 0f, 0f))
        assertEquals(SemanticIntent.KNOWLEDGE_QUERY, result.frame?.intent)
        assertEquals(setOf(ToolFamily.KNOWLEDGE), result.frame?.domains)
    }

    @Test
    fun `conversation never asserts a capability domain even with a high domain score`() {
        val result = engine().classify(floatArrayOf(0f, 0f, 6f, 0f))
        assertEquals(SemanticIntent.CONVERSATION, result.frame?.intent)
        assertEquals(emptySet(), result.frame?.domains)
    }

    @Test
    fun `a confident ellipsis reference mode empties domains even though the domain head scores high`() {
        // dimension 3 drives referenceMode toward ELLIPSIS (weight -1 vs PARTITIVE's +1);
        // dimension 0 alone would otherwise resolve CAPABILITY_QUERY with a real domain.
        val result = engine().classify(floatArrayOf(6f, 0f, 0f, -6f))
        assertEquals(SemanticIntent.CAPABILITY_QUERY, result.frame?.intent)
        assertEquals(ReferenceMode.ELLIPSIS, result.frame?.referenceMode)
        assertEquals(emptySet(), result.frame?.domains)
    }

    @Test
    fun `a confident partitive reference mode also empties domains`() {
        val result = engine().classify(floatArrayOf(6f, 0f, 0f, 6f))
        assertEquals(ReferenceMode.PARTITIVE, result.frame?.referenceMode)
        assertEquals(emptySet(), result.frame?.domains)
    }

    @Test
    fun `capability query with no reference-mode signal keeps the domain head result`() {
        val result = engine().classify(floatArrayOf(6f, 0f, 0f, 0f))
        assertEquals(ReferenceMode.NONE, result.frame?.referenceMode)
        assertEquals(setOf(ToolFamily.WEATHER, ToolFamily.AGENDA), result.frame?.domains)
    }

    @Test
    fun `operation head resolves CREATE vs DELETE independently of intent`() {
        val create = engine().classify(floatArrayOf(6f, 0f, 0f, 6f))
        assertEquals(SemanticOperation.CREATE, create.frame?.operation)
        val delete = engine().classify(floatArrayOf(6f, 0f, 0f, -6f))
        assertEquals(SemanticOperation.DELETE, delete.frame?.operation)
    }

    @Test
    fun `missing operation head degrades to UNKNOWN operation, never a guess`() {
        val engineWithoutOperation = LearnedHeadClassifierEngine(export(includeOperation = false))
        val result = engineWithoutOperation.classify(floatArrayOf(6f, 0f, 0f, 0f))
        assertEquals(SemanticOperation.UNKNOWN, result.frame?.operation)
    }

    @Test
    fun `missing referenceMode head degrades to NONE, never blocks classification`() {
        val engineWithoutReferenceMode = LearnedHeadClassifierEngine(export(includeReferenceMode = false))
        val result = engineWithoutReferenceMode.classify(floatArrayOf(6f, 0f, 0f, 0f))
        assertEquals(ReferenceMode.NONE, result.frame?.referenceMode)
        assertEquals(setOf(ToolFamily.WEATHER, ToolFamily.AGENDA), result.frame?.domains)
    }

    @Test
    fun `previousFrame is accepted but never changes the current turn's classification`() {
        val previous = engine().classify(floatArrayOf(0f, 6f, 0f, 0f)).frame
        val withPrevious = engine().classify(floatArrayOf(6f, 0f, 0f, 0f), previous)
        val withoutPrevious = engine().classify(floatArrayOf(6f, 0f, 0f, 0f), null)
        assertEquals(withoutPrevious, withPrevious)
    }

    @Test
    fun `an unrecognized intent label string is treated as OOD, never crashes`() {
        val badExport = export().copy(
            intent = ExportedHead(
                architecture = "linear",
                labels = listOf("NOT_A_REAL_INTENT"),
                linear = LinearWeights(weight = listOf(listOf(0f, 0f, 0f, 0f)), bias = listOf(0f)),
            ),
        )
        val result = LearnedHeadClassifierEngine(badExport).classify(floatArrayOf(1f, 1f, 1f, 1f))
        assertEquals(true, result.ood)
        assertEquals(null, result.frame)
    }

    @Test
    fun `an empty intent label list is unavailable, never an index-out-of-bounds crash`() {
        val emptyExport = export().copy(
            intent = ExportedHead("linear", emptyList(), LinearWeights(emptyList(), emptyList())),
        )
        val result = LearnedHeadClassifierEngine(emptyExport).classify(floatArrayOf(1f, 1f, 1f, 1f))
        assertEquals(true, result.ood)
        assertEquals(null, result.frame)
        assertEquals("LEARNED_HEAD_NO_INTENT_LABELS", result.failureReason)
    }
}
