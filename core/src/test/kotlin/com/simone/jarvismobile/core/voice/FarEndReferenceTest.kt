package com.simone.jarvismobile.core.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FarEndReferenceTest {

    private fun pcm(n: Int) = FloatArray(n) { it / 1000f }

    private fun recorder(gen: Int = 1, rate: Int = 22_050) = FarEndReferenceRecorder().also { it.begin(gen, rate) }

    // ---- frame ----

    @Test fun frameCopiesAcceptedRegionAndIsImmutable() {
        val src = pcm(10)
        val f = FarEndPcmFrame.copyOf(src, 2, 4, 1, 0, 0, 22_050)!!
        src[2] = 99f
        assertEquals(0.002f, f.sampleAt(0))
        val copy = f.copyOfSamples(); copy[0] = 7f
        assertEquals(0.002f, f.sampleAt(0))
        assertEquals(4, f.sampleCount)
    }

    @Test fun frameRejectsInvalidArguments() {
        val src = pcm(10)
        assertNull(FarEndPcmFrame.copyOf(src, 0, 0, 1, 0, 0, 16000))
        assertNull(FarEndPcmFrame.copyOf(src, 0, 11, 1, 0, 0, 16000))
        assertNull(FarEndPcmFrame.copyOf(src, 8, 3, 1, 0, 0, 16000))
        assertNull(FarEndPcmFrame.copyOf(src, -1, 3, 1, 0, 0, 16000))
        assertNull(FarEndPcmFrame.copyOf(src, 0, 3, 1, -1, 0, 16000))
        assertNull(FarEndPcmFrame.copyOf(src, 0, 3, 1, 0, -1, 16000))
        assertNull(FarEndPcmFrame.copyOf(src, 0, 3, 1, 0, 0, 0))
        assertNull(FarEndPcmFrame.copyOf(FloatArray(FarEndPcmFrame.MAX_FRAME_SAMPLES + 1), 0, FarEndPcmFrame.MAX_FRAME_SAMPLES + 1, 1, 0, 0, 16000))
        assertNotNull(FarEndPcmFrame.copyOf(FloatArray(FarEndPcmFrame.MAX_FRAME_SAMPLES), 0, FarEndPcmFrame.MAX_FRAME_SAMPLES, 1, 0, 0, 16000))
    }

    @Test fun toStringNeverPrintsSamplesAndHasNoContentFields() {
        val f = FarEndPcmFrame.copyOf(floatArrayOf(0.123456f, 0.5f), 0, 2, 3, 4, 5, 22_050)!!
        assertFalse(f.toString().contains("0.1234"))
        assertEquals(FarEndGainStage.PRE_OUTPUT_GAIN, f.gainStage)
        val names = FarEndPcmFrame::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(names.none { it.contains("text", true) || it.contains("device", true) || it.contains("time", true) }, names.toString())
    }

    // ---- write loop / recorder ----

    @Test fun oneSuccessfulWriteYieldsOneCorrectReference() = runTest(UnconfinedTestDispatcher()) {
        val r = recorder()
        val got = ArrayList<FarEndPcmFrame>()
        val job = launch { r.frames.collect { got += it } }
        val src = pcm(100)
        val ok = PlaybackWriteLoop.write(src, 8192, 1, { false }, { _, _, len -> len }, r)
        job.cancel()
        assertTrue(ok)
        assertEquals(1, got.size)
        assertEquals(0L, got[0].sequence); assertEquals(0L, got[0].sampleOffset)
        assertEquals(100, got[0].sampleCount); assertEquals(22_050, got[0].sampleRateHz)
        assertEquals(src[57], got[0].sampleAt(57))
    }

    @Test fun partialWritesEmitOnlyAcceptedSamplesWithMonotonicOffsets() = runTest(UnconfinedTestDispatcher()) {
        val r = recorder()
        val got = ArrayList<FarEndPcmFrame>()
        val job = launch { r.frames.collect { got += it } }
        val src = pcm(10)
        val accept = intArrayOf(3, 2, 5)
        var i = 0
        PlaybackWriteLoop.write(src, 8192, 1, { false }, { _, _, _ -> accept[i++] }, r)
        job.cancel()
        assertEquals(listOf(3, 2, 5), got.map { it.sampleCount })
        assertEquals(listOf(0L, 3L, 5L), got.map { it.sampleOffset })
        assertEquals(listOf(0L, 1L, 2L), got.map { it.sequence })
        assertEquals(src[3], got[1].sampleAt(0)) // exactly the accepted region
        assertEquals(10L, r.snapshot().samplesAccepted)
    }

    @Test fun failedWriteEmitsNoReference() {
        val r = recorder()
        var err = 0
        val ok = PlaybackWriteLoop.write(pcm(10), 8192, 1, { false }, { _, _, _ -> -1 }, r, { err = it })
        assertFalse(ok); assertEquals(-1, err)
        assertEquals(0L, r.snapshot().framesAccepted)
        val zero = PlaybackWriteLoop.write(pcm(10), 8192, 1, { false }, { _, _, _ -> 0 }, r)
        assertFalse(zero); assertEquals(0L, r.snapshot().framesAccepted)
    }

    @Test fun sinkExceptionIsAFailureNotACrash() {
        val r = recorder()
        assertFalse(PlaybackWriteLoop.write(pcm(4), 8192, 1, { false }, { _, _, _ -> error("boom") }, r))
        assertEquals(0L, r.snapshot().framesAccepted)
    }

    @Test fun newGenerationResetsAndStaleGenerationIsRejected() {
        val r = recorder(gen = 1)
        PlaybackWriteLoop.write(pcm(10), 8192, 1, { false }, { _, _, l -> l }, r)
        r.begin(2, 16_000)
        assertEquals(0L, r.snapshot().framesAccepted)
        assertFalse(r.onAcceptedWrite(1, pcm(4), 0, 4)) // stale gen 1
        assertTrue(r.onAcceptedWrite(2, pcm(4), 0, 4))
        assertEquals(1L, r.snapshot().framesAccepted)
        assertEquals(16_000, r.snapshot().sampleRateHz)
    }

    @Test fun stopPreventsSubsequentReference() {
        val r = recorder()
        assertTrue(r.onAcceptedWrite(1, pcm(4), 0, 4))
        r.end()
        assertFalse(r.onAcceptedWrite(1, pcm(4), 0, 4))
        assertEquals(FarEndReferenceCapability.IDLE, r.capability())
    }

    @Test fun loopStopsWhenFlaggedStoppedAndPlaybackSamplesUnchanged() {
        val r = recorder()
        val src = pcm(20_000)
        val written = ArrayList<Float>()
        var stop = false
        val ok = PlaybackWriteLoop.write(src, 8192, 1, { stop }, { b, o, l ->
            for (k in o until o + l) written += b[k]
            stop = true
            l
        }, r)
        assertFalse(ok)
        assertEquals(8192, written.size) // chunking unchanged
        assertEquals(src[100], written[100])
        assertEquals(1L, r.snapshot().framesAccepted)
    }

    @Test fun replayIsZeroLateSubscriberSeesNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = recorder()
        r.onAcceptedWrite(1, pcm(4), 0, 4)
        val got = ArrayList<FarEndPcmFrame>()
        val job = launch { r.frames.collect { got += it } }
        job.cancel()
        assertTrue(got.isEmpty())
    }

    @Test fun slowConsumerNeverBlocksAndDropsAreCounted() = runTest {
        val r = FarEndReferenceRecorder(extraBuffer = 2); r.begin(1, 16_000)
        // A subscriber that never receives (not started) is simulated by a started-but-suspended collector.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { r.frames.collect { gate.await() } }
        repeat(20) { assertTrue(r.onAcceptedWrite(1, pcm(8), 0, 8)) } // never blocks
        val s = r.snapshot()
        assertEquals(20L, s.framesAccepted)
        assertTrue(s.framesDropped > 0)
        assertEquals(160L, s.samplesAccepted) // timeline advances regardless of drops
        gate.complete(Unit); job.cancel()
    }

    // ---- capability / snapshot ----

    @Test fun capabilityFollowsRealPlayback() {
        val r = FarEndReferenceRecorder()
        assertEquals(FarEndReferenceCapability.IDLE, r.capability())
        r.begin(1, 22_050)
        assertEquals(FarEndReferenceCapability.AVAILABLE_PCM, r.capability())
        r.end()
        assertEquals(FarEndReferenceCapability.IDLE, r.capability())
    }

    @Test fun snapshotCarriesNoSamplesAndExposesGain() {
        val r = recorder(); r.setOutputGain(0.4f)
        r.onAcceptedWrite(1, pcm(4), 0, 4)
        val s = r.snapshot()
        assertEquals(0.4f, s.outputGain); assertEquals(4, s.lastFrameSamples)
        assertTrue(FarEndReferenceSnapshot::class.java.declaredFields.none { it.type.isArray })
    }
}
