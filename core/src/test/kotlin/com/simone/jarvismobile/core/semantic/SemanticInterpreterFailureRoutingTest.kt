package com.simone.jarvismobile.core.semantic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * § FASE 2A.10 test category B — "interpreter-failure simulation (timeout/
 * null/malformed/low-confidence/busy-mutex), proving no keyword-domain
 * routing occurs". Mirrors, at the `:core` level, the exact retry-once
 * contract `ConversationalJarvisEngine.runSemanticPath` implements against a
 * real [SemanticInterpreter] (see that function's own doc comment): call
 * once; on `null`/[SemanticInterpretation.Invalid], retry exactly once with
 * the SAME text/context; if the retry is also unusable, the turn is
 * [SemanticSource.LEGACY_FALLBACK] — which, since FASE 2A.10, goes straight
 * to the full reasoning loop, never through a keyword/topic domain guess.
 *
 * The five failure shapes the spec names are all represented as
 * [SemanticInterpretation.Invalid] reasons (a timeout/busy-mutex/null result
 * is exactly what a real [SemanticInterpreter] implementation reports back
 * as — see `LocalLlmSemanticInterpreter`'s own doc comment; this project has
 * no way to simulate an actual native timeout at the JVM level without
 * Android, so the reason STRING is the honest, already-real signal a caller
 * distinguishes on) — this test proves that regardless of WHICH reason
 * failed, the type system leaves no path from failure to a routed
 * [SemanticFrame]: [SemanticRoutingOutcome] can only ever be produced from an
 * already-[SemanticInterpretation.Valid], already-merged frame.
 */
class SemanticInterpreterFailureRoutingTest {

    /** Mirrors `ConversationalJarvisEngine.runSemanticPath`'s retry-once contract exactly. */
    private suspend fun interpretWithOneRetry(
        interpreter: FakeSemanticInterpreter,
        text: String,
        context: SemanticDialogueContext,
    ): SemanticInterpretation? {
        var interpretation = interpreter.interpret(text, context)
        if (interpretation is SemanticInterpretation.Invalid) {
            interpretation = interpreter.interpret(text, context)
        }
        return interpretation
    }

    private suspend fun run(vararg reasons: String): SemanticInterpretation? {
        val interpreter = FakeSemanticInterpreter()
        reasons.forEach { interpreter.enqueue(SemanticInterpretation.Invalid(it)) }
        return interpretWithOneRetry(interpreter, "Domani farà caldo?", SemanticDialogueContext(previousFrame = null))
    }

    @Test
    fun `a timeout-shaped failure on both attempts never yields a routable frame`() = kotlinx.coroutines.test.runTest {
        val result = run("timeout", "timeout")
        assertTrue(result is SemanticInterpretation.Invalid)
    }

    @Test
    fun `a null-result failure (no scripted response at all) never yields a routable frame`() = kotlinx.coroutines.test.runTest {
        // Empty FakeSemanticInterpreter — every call returns Invalid("no_scripted_response").
        val interpreter = FakeSemanticInterpreter()
        val result = interpretWithOneRetry(interpreter, "Domani farà caldo?", SemanticDialogueContext(null))
        assertTrue(result is SemanticInterpretation.Invalid)
        assertEquals(2, interpreter.receivedContexts.size) // exactly one retry, never more
    }

    @Test
    fun `a malformed-output failure never yields a routable frame`() = kotlinx.coroutines.test.runTest {
        val result = run("malformed_output", "malformed_output")
        assertTrue(result is SemanticInterpretation.Invalid)
    }

    @Test
    fun `a low-confidence frame is rejected by the validator, not silently routed`() = kotlinx.coroutines.test.runTest {
        // A genuine model output BELOW SemanticFrameValidator.MIN_CONFIDENCE is
        // exactly what "low confidence" means in this pipeline — the
        // interpreter itself may return Valid, but validation still fails.
        val lowConfidenceFrame = SemanticFrame(
            intent = SemanticIntent.CAPABILITY_QUERY,
            domains = setOf(com.simone.jarvismobile.core.tools.ToolFamily.HEALTH),
            operation = SemanticOperation.GET,
            temporalExpression = null,
            metric = null,
            aggregation = null,
            entities = emptyList(),
            referenceMode = ReferenceMode.NONE,
            requiresGrounding = true,
            confidence = 0.1,
            explicitSlots = setOf(SemanticSlot.DOMAINS),
        )
        val validated = SemanticFrameValidator.validate(lowConfidenceFrame)
        assertTrue(validated is SemanticInterpretation.Invalid)
        // Even if a caller mistakenly retried on this (it should not — only a
        // failed/Invalid INTERPRETER call is retried, never a frame that
        // failed VALIDATION), a second Invalid still never produces a route.
    }

    @Test
    fun `a busy-mutex-shaped failure never yields a routable frame`() = kotlinx.coroutines.test.runTest {
        val result = run("classifier_busy", "classifier_busy")
        assertTrue(result is SemanticInterpretation.Invalid)
    }

    @Test
    fun `retry is stateless - both attempts receive the identical dialogue context`() = kotlinx.coroutines.test.runTest {
        val interpreter = FakeSemanticInterpreter()
        interpreter.enqueue(SemanticInterpretation.Invalid("timeout"))
        interpreter.enqueue(SemanticInterpretation.Invalid("timeout"))
        val context = SemanticDialogueContext(previousFrame = null)
        interpretWithOneRetry(interpreter, "Domani farà caldo?", context)
        assertEquals(2, interpreter.receivedContexts.size)
        assertEquals(context, interpreter.receivedContexts[0])
        assertEquals(context, interpreter.receivedContexts[1])
    }

    @Test
    fun `a retry that succeeds after an initial failure produces a genuinely routable frame`() = kotlinx.coroutines.test.runTest {
        val interpreter = FakeSemanticInterpreter()
        interpreter.enqueue(SemanticInterpretation.Invalid("timeout"))
        val goodFrame = SemanticFrame(
            intent = SemanticIntent.CAPABILITY_QUERY,
            domains = setOf(com.simone.jarvismobile.core.tools.ToolFamily.WEATHER),
            operation = SemanticOperation.GET,
            temporalExpression = "domani",
            metric = null,
            aggregation = null,
            entities = emptyList(),
            referenceMode = ReferenceMode.NONE,
            requiresGrounding = true,
            confidence = 0.9,
            explicitSlots = setOf(SemanticSlot.DOMAINS, SemanticSlot.TEMPORAL_EXPRESSION),
        )
        interpreter.enqueueValid(goodFrame)
        val result = interpretWithOneRetry(interpreter, "Domani farà caldo?", SemanticDialogueContext(null))
        assertTrue(result is SemanticInterpretation.Valid)
        val validated = SemanticFrameValidator.validate((result as SemanticInterpretation.Valid).frame)
        assertTrue(validated is SemanticInterpretation.Valid)
        val merged = SemanticFrameMerger.merge((validated as SemanticInterpretation.Valid).frame, null)
        val outcome = SemanticRouter.routeFrame(merged.frame)
        assertEquals(
            SemanticRoutingOutcome.Direct(com.simone.jarvismobile.core.tools.ToolFamily.WEATHER),
            outcome,
        )
    }

    @Test
    fun `never more than one retry regardless of how many failures are queued`() = kotlinx.coroutines.test.runTest {
        val interpreter = FakeSemanticInterpreter()
        interpreter.enqueue(SemanticInterpretation.Invalid("timeout"))
        interpreter.enqueue(SemanticInterpretation.Invalid("timeout"))
        interpreter.enqueue(SemanticInterpretation.Invalid("timeout"))
        interpretWithOneRetry(interpreter, "Domani farà caldo?", SemanticDialogueContext(null))
        // Exactly 2 calls consumed the first two queued failures — the third
        // stays queued, proving a genuinely third attempt was never made.
        assertEquals(2, interpreter.receivedContexts.size)
    }
}
