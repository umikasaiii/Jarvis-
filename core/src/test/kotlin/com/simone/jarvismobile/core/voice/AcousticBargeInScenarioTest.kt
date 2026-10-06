package com.simone.jarvismobile.core.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ScenarioCapture(val failure: VoiceCaptureFailure? = null) : DuplexCapturePort {
    val flow = MutableSharedFlow<VoicePcmFrame>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    override val frames: SharedFlow<VoicePcmFrame> = flow.asSharedFlow()
    var released = false
    private val stop = CompletableDeferred<Unit>()
    override suspend fun runUntilCancelled(): VoiceCaptureOutcome {
        if (failure != null) { released = true; return VoiceCaptureOutcome(failure, "failed", 0) }
        try { stop.await() } finally { released = true }
        return VoiceCaptureOutcome(VoiceCaptureFailure.CANCELLED, "cancelled", 0)
    }
    override fun cancel() { stop.complete(Unit) }
}

private class LevelVad : VoiceActivityDetector {
    override suspend fun process(frame: VoicePcmFrame) = if (frame.sampleAt(0).toInt() >= 1000) VadObservation.SPEECH else VadObservation.NON_SPEECH
    override fun reset() {}
}

/** LV-R1 acoustic barge-in scenarios on the platform-free duplex stack (fake AEC passes audio through). */
@OptIn(ExperimentalCoroutinesApi::class)
class AcousticBargeInScenarioTest {
    private val far = MutableSharedFlow<FarEndPcmFrame>(extraBufferCapacity = 64)
    private fun frame(seq: Long, v: Short) = VoicePcmFrame.copyOf(ShortArray(512) { v }, 512, 16_000, seq)!!

    private fun monitor(cap: ScenarioCapture, scope: kotlinx.coroutines.CoroutineScope) =
        LiveDuplexMonitor(cap, far.asSharedFlow(), AecDuplexPipeline(FakeEchoCanceller()), LevelVad(), VadTurnPolicy(VadTurnConfig(2, 3)), scope, 200)

    @Test fun userSpeaksLateInTheUtterance() = runTest {
        val cap = ScenarioCapture(); val m = monitor(cap, backgroundScope); var ev = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { ev++ } }
        m.start(); testScheduler.runCurrent()
        for (s in 0L until 200L) { cap.flow.emit(frame(s, if (s >= 150) 3000 else 0)); testScheduler.runCurrent() }
        assertEquals(1, ev)
    }

    @Test fun userSpeaksImmediatelyAtTheStart() = runTest {
        val cap = ScenarioCapture(); val m = monitor(cap, backgroundScope); var ev = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { ev++ } }
        m.start(); testScheduler.runCurrent()
        for (s in 0L until 10L) { cap.flow.emit(frame(s, 3000)); testScheduler.runCurrent() }
        assertEquals(1, ev)
    }

    @Test fun doubleTalkStillInterruptsOnce() = runTest {
        val cap = ScenarioCapture(); val m = monitor(cap, backgroundScope); var ev = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { ev++ } }
        m.start(); testScheduler.runCurrent()
        for (s in 0L until 60L) { cap.flow.emit(frame(s, if (s % 2 == 0L) 2500 else 1500)); testScheduler.runCurrent() }
        assertEquals(1, ev)
    }

    @Test fun repeatedInterruptionAcrossRunsEachYieldsOneEventAndFullRelease() = runTest {
        repeat(3) {
            val cap = ScenarioCapture(); val m = monitor(cap, backgroundScope); var ev = 0
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { ev++ } }
            m.start(); testScheduler.runCurrent()
            for (s in 0L until 12L) { cap.flow.emit(frame(s, 3000)); testScheduler.runCurrent() }
            assertEquals(1, ev)
            assertEquals(DuplexReleaseResult.RELEASED, m.stopAndAwaitRelease())
            assertTrue(cap.released)
            job.cancel()
        }
    }

    @Test fun captureFailureGoesToErrorWithoutAnyInterruption() = runTest {
        val cap = ScenarioCapture(failure = VoiceCaptureFailure.INITIALIZATION_FAILED); val m = monitor(cap, backgroundScope); var ev = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { ev++ } }
        m.start(); testScheduler.runCurrent()
        assertEquals(DuplexState.ERROR, m.state.value); assertEquals(0, ev)
        assertEquals(VoiceCaptureFailure.INITIALIZATION_FAILED, m.snapshot.value.captureFailure)
    }

    @Test fun silenceForALongUtteranceNeverInterrupts() = runTest {
        val cap = ScenarioCapture(); val m = monitor(cap, backgroundScope); var ev = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { ev++ } }
        m.start(); testScheduler.runCurrent()
        for (s in 0L until 600L) { cap.flow.emit(frame(s, 0)); testScheduler.runCurrent() }
        assertEquals(0, ev); assertEquals(DuplexState.MONITORING, m.state.value)
        assertEquals(DuplexReleaseResult.RELEASED, m.stopAndAwaitRelease())
    }
}
