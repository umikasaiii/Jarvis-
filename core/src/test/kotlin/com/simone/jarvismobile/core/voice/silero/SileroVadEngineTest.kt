package com.simone.jarvismobile.core.voice.silero

import com.simone.jarvismobile.core.voice.VadObservation
import com.simone.jarvismobile.core.voice.VoicePcmFrame
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deterministic fake: records exactly what the engine feeds, scripted probabilities, state = counter-filled. */
private class FakeBackend(var probabilities: ArrayDeque<Float> = ArrayDeque(), var failure: SileroFailure? = null, var throwOnRun: Boolean = false) : SileroInferenceBackend {
    val inputs = mutableListOf<FloatArray>()
    val statesSeen = mutableListOf<FloatArray>()
    val srSeen = mutableListOf<Long>()
    var closes = 0
    var counter = 0f
    var nextStateSize = SileroModelManifest.STATE_FLOATS
    override fun run(input: FloatArray, state: FloatArray, sampleRateHz: Long): SileroInferenceResult {
        inputs += input.copyOf(); statesSeen += state.copyOf(); srSeen += sampleRateHz
        if (throwOnRun) error("boom")
        failure?.let { return SileroInferenceResult.Failed(it) }
        counter += 1f
        return SileroInferenceResult.Ok(probabilities.removeFirstOrNull() ?: 0.1f, FloatArray(nextStateSize) { counter })
    }
    override fun close() { closes++ }
}

private fun frame(seq: Long, base: Int, count: Int = 512, rate: Int = 16_000, channels: Int = 1): VoicePcmFrame {
    val s = ShortArray(count) { (base * 1000 + it).toShort() }
    return VoicePcmFrame.copyOf(s, count, rate, seq, channels)!!
}

class SileroVadEngineTest {
    private fun engine(b: FakeBackend, threshold: Float = 0.5f, clock: () -> Long = System::nanoTime) =
        SileroVadEngine(b, SileroVadConfig(threshold), clock)

    private fun SileroVadEngine.p(f: VoicePcmFrame) = runBlocking { process(f) }

    @Test fun pcmNormalization() {
        assertEquals(0f, SileroPcm.toFloat(0))
        assertEquals(32767 / 32768f, SileroPcm.toFloat(32767))
        assertEquals(-1f, SileroPcm.toFloat(Short.MIN_VALUE))
        assertEquals(0.5f, SileroPcm.toFloat(16384))
        assertEquals(-0.25f, SileroPcm.toFloat(-8192))
        assertTrue(SileroPcm.toFloat(32767) < 1f)
    }

    @Test fun firstFrameGetsSixtyFourZeroContextPlusFrame() {
        val b = FakeBackend(); val e = engine(b)
        val f0 = frame(0, 1)
        e.p(f0)
        val input = b.inputs[0]
        assertEquals(576, input.size)
        assertContentEquals(FloatArray(64), input.copyOfRange(0, 64))
        assertEquals(SileroPcm.toFloat(f0.sampleAt(0)), input[64])
        assertEquals(SileroPcm.toFloat(f0.sampleAt(511)), input[575])
        assertEquals(16_000L, b.srSeen[0])
    }

    @Test fun contextIsTailOfPreviousFrameNotOfTheCombinedBuffer() {
        val b = FakeBackend(); val e = engine(b)
        val f0 = frame(0, 1); val f1 = frame(1, 2); val f2 = frame(2, 3)
        e.p(f0); e.p(f1); e.p(f2)
        fun tail(f: VoicePcmFrame) = FloatArray(64) { SileroPcm.toFloat(f.sampleAt(448 + it)) }
        assertContentEquals(tail(f0), b.inputs[1].copyOfRange(0, 64))
        assertContentEquals(tail(f1), b.inputs[2].copyOfRange(0, 64))
        assertEquals(SileroPcm.toFloat(f2.sampleAt(0)), b.inputs[2][64])
    }

    @Test fun resetRestoresZeroContextAndZeroState() {
        val b = FakeBackend(); val e = engine(b)
        e.p(frame(0, 1)); e.p(frame(1, 2))
        e.reset()
        e.p(frame(0, 3)) // new generation restarts at 0
        assertContentEquals(FloatArray(64), b.inputs[2].copyOfRange(0, 64))
        assertContentEquals(FloatArray(256), b.statesSeen[2])
    }

    @Test fun generationChangeCannotLeakPreviousContext() {
        val b = FakeBackend(); val e = engine(b)
        e.p(frame(0, 9)); e.p(frame(1, 9))
        e.reset()
        e.p(frame(0, 5))
        assertTrue(b.inputs[2].copyOfRange(0, 64).all { it == 0f })
    }

    @Test fun duplicateAndStaleFramesDoNotAdvanceContextOrState() {
        val b = FakeBackend(); val e = engine(b)
        e.p(frame(5, 1)); e.p(frame(6, 2))
        val before = b.inputs.size
        assertEquals(VadObservation.UNKNOWN, e.p(frame(6, 7))) // duplicate
        assertEquals(VadObservation.UNKNOWN, e.p(frame(3, 7))) // decreasing
        assertEquals(before, b.inputs.size) // backend never called
        e.p(frame(7, 3))
        val f6 = frame(6, 2)
        assertContentEquals(FloatArray(64) { SileroPcm.toFloat(f6.sampleAt(448 + it)) }, b.inputs.last().copyOfRange(0, 64))
        assertEquals(2f, b.statesSeen.last()[0]) // state advanced exactly twice before
        assertEquals(2L, e.stats().framesRejectedStale)
    }

    @Test fun forwardSequenceGapBreaksContinuityDeterministically() {
        val b = FakeBackend(); val e = engine(b)
        e.p(frame(0, 1)); e.p(frame(1, 2))
        e.p(frame(5, 3)) // frames 2..4 never existed
        assertContentEquals(FloatArray(64), b.inputs[2].copyOfRange(0, 64))
        assertContentEquals(FloatArray(256), b.statesSeen[2])
        assertEquals(1L, e.stats().continuityResets)
        e.p(frame(6, 4)) // continuous again: context is real
        assertTrue(b.inputs[3].copyOfRange(0, 64).any { it != 0f })
    }

    @Test fun recurrentStateStartsZeroAndFeedsBackWithinGeneration() {
        val b = FakeBackend(); val e = engine(b)
        e.p(frame(0, 1)); e.p(frame(1, 2)); e.p(frame(2, 3))
        assertContentEquals(FloatArray(256), b.statesSeen[0])
        assertTrue(b.statesSeen[1].all { it == 1f })
        assertTrue(b.statesSeen[2].all { it == 2f })
    }

    @Test fun failedInferenceInstallsNoStateAndNoContext() {
        val b = FakeBackend(); val e = engine(b)
        e.p(frame(0, 1)); b.failure = SileroFailure.INFERENCE_FAILED
        assertEquals(VadObservation.UNKNOWN, e.p(frame(1, 2)))
        b.failure = null
        e.p(frame(2, 3))
        assertTrue(b.statesSeen[2].all { it == 1f }) // still the state from frame 0
        val f0 = frame(0, 1)
        assertContentEquals(FloatArray(64) { SileroPcm.toFloat(f0.sampleAt(448 + it)) }, b.inputs[2].copyOfRange(0, 64))
        assertEquals(SileroFailure.INFERENCE_FAILED, e.stats().lastFailure)
    }

    @Test fun backendExceptionIsContained() {
        val b = FakeBackend(throwOnRun = true); val e = engine(b)
        assertEquals(VadObservation.UNKNOWN, e.p(frame(0, 1)))
        assertEquals(SileroFailure.INFERENCE_FAILED, e.stats().lastFailure)
    }

    @Test fun invalidProbabilitiesAreUnknownAndDoNotAdvanceState() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f)) {
            val b = FakeBackend(ArrayDeque(listOf(bad, 0.9f))); val e = engine(b)
            assertEquals(VadObservation.UNKNOWN, e.p(frame(0, 1)), "p=$bad")
            assertEquals(SileroFailure.INVALID_OUTPUT, e.stats().lastFailure)
            e.p(frame(1, 2))
            assertContentEquals(FloatArray(256), b.statesSeen[1]) // not advanced
            assertContentEquals(FloatArray(64), b.inputs[1].copyOfRange(0, 64))
        }
    }

    @Test fun wrongStateSizeFromBackendIsInvalidOutput() {
        val b = FakeBackend().also { it.nextStateSize = 10 }; val e = engine(b)
        assertEquals(VadObservation.UNKNOWN, e.p(frame(0, 1)))
        assertEquals(SileroFailure.INVALID_OUTPUT, e.stats().lastFailure)
    }

    @Test fun probabilityMapsToObservationWithExplicitThreshold() {
        val b = FakeBackend(ArrayDeque(listOf(0.02f, 0.9f, 0.5f, 0.4999f))); val e = engine(b, 0.5f)
        assertEquals(VadObservation.NON_SPEECH, e.p(frame(0, 1)))
        assertEquals(VadObservation.SPEECH, e.p(frame(1, 1)))
        assertEquals(VadObservation.SPEECH, e.p(frame(2, 1))) // exact boundary is >=
        assertEquals(VadObservation.NON_SPEECH, e.p(frame(3, 1)))
    }

    @Test fun onlyCanonicalFramesAreAccepted() {
        val b = FakeBackend(); val e = engine(b)
        assertEquals(VadObservation.UNKNOWN, e.p(frame(0, 1, rate = 8_000)))
        assertEquals(VadObservation.UNKNOWN, e.p(frame(1, 1, channels = 2)))
        assertEquals(VadObservation.UNKNOWN, e.p(frame(2, 1, count = 480)))
        assertEquals(VadObservation.UNKNOWN, e.p(frame(3, 1, count = 1024)))
        assertTrue(b.inputs.isEmpty())
        assertEquals(SileroFailure.UNSUPPORTED_INPUT, e.stats().lastFailure)
        assertEquals(VadObservation.NON_SPEECH, e.p(frame(4, 1))) // rejected frames did not consume sequences
    }

    @Test fun closeIsIdempotentAndBlocksFurtherInference() {
        val b = FakeBackend(); val e = engine(b)
        e.p(frame(0, 1)); e.close(); e.close()
        assertEquals(1, b.closes)
        assertEquals(VadObservation.UNKNOWN, e.p(frame(1, 1)))
        assertEquals(SileroFailure.MODEL_CLOSED, e.stats().lastFailure)
        assertEquals(1, b.inputs.size)
    }

    @Test fun frameIsNeverMutated() {
        val b = FakeBackend(); val e = engine(b)
        val f = frame(0, 4); val before = f.copyOfSamples()
        e.p(f)
        assertContentEquals(before, f.copyOfSamples())
    }

    @Test fun timingIsAggregatedBoundedAndFromInjectedMonotonicClock() {
        var t = 0L
        val b = FakeBackend(ArrayDeque(listOf(0.1f, 0.3f, 0.5f)))
        val e = engine(b, clock = { t += 2_000_000; t }) // each read +2ms => each inference measures 2ms
        e.p(frame(0, 1)); e.p(frame(1, 1)); e.p(frame(2, 1))
        val s = e.stats()
        assertEquals(3, s.framesInferred)
        assertEquals(2.0, s.averageInferenceMs!!, 1e-9)
        assertEquals(2.0, s.maxInferenceMs, 1e-9)
        assertEquals(0.5f, s.maxProbability)
        assertEquals(0.3f, s.meanProbability!!, 1e-6f)
    }

    @Test fun resetClearsStatsAndStatsExposeNoPcm() {
        val b = FakeBackend(); val e = engine(b)
        e.p(frame(0, 1)); e.reset()
        assertEquals(SileroEngineStats(), e.stats())
        val fields = SileroEngineStats::class.java.declaredFields.map { it.type }
        assertTrue(fields.none { it.isArray || Collection::class.java.isAssignableFrom(it) || it == String::class.java })
        assertNull(e.stats().lastFailure)
    }

    @Test fun backendIsLoadedOnceByConstructionAndNeverPerFrame() {
        val b = FakeBackend(); val e = engine(b)
        repeat(5) { e.p(frame(it.toLong(), 1)) }
        assertEquals(5, b.inputs.size)
        assertEquals(0, b.closes)
    }
}
