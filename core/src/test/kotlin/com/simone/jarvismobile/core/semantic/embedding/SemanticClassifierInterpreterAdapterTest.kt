package com.simone.jarvismobile.core.semantic.embedding

import com.simone.jarvismobile.core.semantic.SemanticDialogueContext
import com.simone.jarvismobile.core.semantic.SemanticInterpretation
import com.simone.jarvismobile.core.semantic.SemanticIntent
import com.simone.jarvismobile.core.tools.ToolFamily
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * § FASE 2A.11 §16 — proves the adapter is a faithful, lossless bridge: a
 * confident classification becomes [SemanticInterpretation.Valid], an OOD
 * one becomes [SemanticInterpretation.Invalid] — the SAME sealed type the
 * existing `runSemanticPath`/`SemanticFrameValidator`/`SemanticFrameMerger`/
 * `SemanticRouter` pipeline already consumes unchanged.
 */
class SemanticClassifierInterpreterAdapterTest {

    private class FakeClassifier(private val result: SemanticClassificationResult) : SemanticClassifier {
        var receivedText: String? = null
        override suspend fun classify(text: String, previousFrame: com.simone.jarvismobile.core.semantic.SemanticFrame?): SemanticClassificationResult {
            receivedText = text
            return result
        }
    }

    @Test
    fun `a confident result becomes Valid, carrying the exact same frame`() = runTest {
        val engine = TestCorpusFixture.buildEngine()
        val result = engine.classify(FakeSemanticEmbedder.embed("Che tempo fa oggi?"))
        val adapter = SemanticClassifierInterpreterAdapter(FakeClassifier(result))
        val interpretation = adapter.interpret("Che tempo fa oggi?", SemanticDialogueContext(null))
        assertTrue(interpretation is SemanticInterpretation.Valid)
        assertEquals(SemanticIntent.CAPABILITY_QUERY, (interpretation as SemanticInterpretation.Valid).frame.intent)
        assertEquals(setOf(ToolFamily.WEATHER), interpretation.frame.domains)
    }

    @Test
    fun `an OOD result becomes Invalid, carrying the classifier's failure reason`() = runTest {
        val adapter = SemanticClassifierInterpreterAdapter(FakeClassifier(SemanticClassificationResult.unavailable("ood:low_confidence:0.1")))
        val interpretation = adapter.interpret("qualcosa di completamente ignoto", SemanticDialogueContext(null))
        assertTrue(interpretation is SemanticInterpretation.Invalid)
        assertEquals("ood:low_confidence:0.1", (interpretation as SemanticInterpretation.Invalid).reason)
    }

    @Test
    fun `a model-unavailable result becomes Invalid with the SEMANTIC_MODEL_UNAVAILABLE reason`() = runTest {
        val adapter = SemanticClassifierInterpreterAdapter(FakeClassifier(SemanticClassificationResult.unavailable()))
        val interpretation = adapter.interpret("qualunque cosa", SemanticDialogueContext(null))
        assertTrue(interpretation is SemanticInterpretation.Invalid)
        assertEquals("SEMANTIC_MODEL_UNAVAILABLE", (interpretation as SemanticInterpretation.Invalid).reason)
    }

    @Test
    fun `the adapter passes the raw text through to the classifier untouched`() = runTest {
        val fake = FakeClassifier(SemanticClassificationResult.unavailable())
        val adapter = SemanticClassifierInterpreterAdapter(fake)
        adapter.interpret("Che impegni ho domani?", SemanticDialogueContext(null))
        assertEquals("Che impegni ho domani?", fake.receivedText)
    }
}
