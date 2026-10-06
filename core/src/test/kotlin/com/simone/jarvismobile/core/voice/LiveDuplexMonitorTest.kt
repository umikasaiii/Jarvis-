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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

private class FakeCapturePort(val releaseNever: Boolean = false) : DuplexCapturePort {
    val flow = MutableSharedFlow<VoicePcmFrame>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    override val frames: SharedFlow<VoicePcmFrame> = flow.asSharedFlow()
    var released = false
    var running = false
    var cancelCount = 0
    private val stop = CompletableDeferred<Unit>()
    override suspend fun runUntilCancelled(): VoiceCaptureOutcome {
        running = true
        try { stop.await() } finally {
            if (!releaseNever) { released = true; running = false }
        }
        if (releaseNever) awaitCancellation()
        return VoiceCaptureOutcome(VoiceCaptureFailure.CANCELLED, "cancelled", 0)
    }
    override fun cancel() { cancelCount++; stop.complete(Unit) }
}

private class ScriptedVad : VoiceActivityDetector {
    var resets = 0
    override suspend fun process(frame: VoicePcmFrame): VadObservation =
        if (frame.sampleAt(0).toInt() >= 1000) VadObservation.SPEECH else VadObservation.NON_SPEECH
    override fun reset() { resets++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveDuplexMonitorTest {
    private fun frame(seq: Long, v: Short) = VoicePcmFrame.copyOf(ShortArray(512) { v }, 512, 16_000, seq)!!
    private val farFlow = MutableSharedFlow<FarEndPcmFrame>(replay = 0, extraBufferCapacity = 64)

    private fun newMonitor(cap: FakeCapturePort, scope: CoroutineScope, canceller: FakeEchoCanceller = FakeEchoCanceller(), vad: ScriptedVad = ScriptedVad(), timeout: Long = 200) =
        LiveDuplexMonitor(cap, farFlow.asSharedFlow(), AecDuplexPipeline(canceller), vad, VadTurnPolicy(VadTurnConfig(2, 3)), scope, timeout)

    @Test fun silenceNeverInterrupts() = runTest {
        val cap = FakeCapturePort(); val m = newMonitor(cap, backgroundScope)
        var events = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { events++ } }
        assertTrue(m.start()); testScheduler.runCurrent()
        for (s in 0L until 30L) cap.flow.emit(frame(s, 0)); testScheduler.runCurrent()
        assertEquals(0, events); assertEquals(DuplexState.MONITORING, m.state.value)
        assertEquals(DuplexReleaseResult.RELEASED, m.stopAndAwaitRelease())
    }

    @Test fun sustainedSpeechEmitsExactlyOneEventPerRun() = runTest {
        val cap = FakeCapturePort(); val m = newMonitor(cap, backgroundScope)
        var events = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { events++ } }
        m.start(); testScheduler.runCurrent()
        for (s in 0L until 40L) cap.flow.emit(frame(s, if (s >= 4) 2000 else 0)); testScheduler.runCurrent()
        assertEquals(1, events); assertEquals(DuplexState.INTERRUPTING, m.state.value)
        assertEquals(1, m.snapshot.value.interruptions)
    }

    @Test fun shortBlipBelowHysteresisDoesNotInterrupt() = runTest {
        val cap = FakeCapturePort(); val m = newMonitor(cap, backgroundScope)
        var events = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { events++ } }
        m.start(); testScheduler.runCurrent()
        for (s in 0L until 30L) cap.flow.emit(frame(s, if (s == 5L || s == 12L) 2000 else 0)); testScheduler.runCurrent()
        assertEquals(0, events)
    }

    @Test fun stopReleasesCaptureBeforeReturningAndVadIsReset() = runTest {
        val cap = FakeCapturePort(); val vad = ScriptedVad(); val c = FakeEchoCanceller()
        val m = newMonitor(cap, backgroundScope, c, vad); m.start(); testScheduler.runCurrent()
        assertTrue(cap.running)
        val r = m.stopAndAwaitRelease()
        assertEquals(DuplexReleaseResult.RELEASED, r); assertTrue(cap.released); assertFalse(cap.running)
        assertEquals(1, c.closeCount); assertEquals(DuplexState.IDLE, m.state.value)
        assertTrue(vad.resets >= 2)
    }

    @Test fun releaseTimeoutIsReportedNeverAssumedAndStateIsError() = runTest {
        val cap = FakeCapturePort(releaseNever = true); val m = newMonitor(cap, backgroundScope, timeout = 50)
        m.start()
        val r = m.stopAndAwaitRelease()
        assertEquals(DuplexReleaseResult.TIMEOUT, r); assertEquals(DuplexState.ERROR, m.state.value)
        assertFalse(cap.released)
    }

    @Test fun stopIsIdempotentAndNeverStartedIsNotRunning() = runTest {
        val cap = FakeCapturePort(); val m = newMonitor(cap, backgroundScope)
        assertEquals(DuplexReleaseResult.NOT_RUNNING, m.stopAndAwaitRelease())
        m.start(); assertEquals(DuplexReleaseResult.RELEASED, m.stopAndAwaitRelease())
        assertEquals(DuplexReleaseResult.RELEASED, m.stopAndAwaitRelease())
    }

    @Test fun secondStartIsRefused() = runTest {
        val m = newMonitor(FakeCapturePort(), backgroundScope)
        assertTrue(m.start()); assertFalse(m.start())
    }

    @Test fun vadSeesOnlyPostAecFrames() = runTest {
        // AEC output is silence (non-speech) while the raw mic is loud: no interruption.
        val cap = FakeCapturePort()
        val cancelling = object : EchoCanceller {
            override var state = EchoCancellerState.READY
            override val nearBlockSamples = 160; override val farBlockSamples = 160
            override fun feedFarEnd(block: FloatArray) = EchoBlockResult.Accepted
            override fun processNearEnd(block: ShortArray) = EchoBlockResult.Ok(ShortArray(block.size))
            override fun close() { state = EchoCancellerState.CLOSED }
        }
        val m = LiveDuplexMonitor(cap, farFlow.asSharedFlow(), AecDuplexPipeline(cancelling), ScriptedVad(), VadTurnPolicy(VadTurnConfig(2, 3)), backgroundScope, 200)
        var events = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { events++ } }
        m.start(); testScheduler.runCurrent()
        for (s in 0L until 40L) cap.flow.emit(frame(s, 20000)); testScheduler.runCurrent() // loud raw (pure echo)
        assertEquals(0, events)
    }

    @Test fun farEndFramesReachTheCanceller() = runTest {
        val c = FakeEchoCanceller(); val m = newMonitor(FakeCapturePort(), backgroundScope, c); m.start(); testScheduler.runCurrent()
        farFlow.emit(FarEndPcmFrame.copyOf(FloatArray(320), 0, 320, 1, 0, 0, 16_000)!!); testScheduler.runCurrent()
        assertEquals(2, c.farBlocks.size)
    }

    @Test fun eligibilityOrderAndAllConditions() {
        val ok = DuplexConditions(true, true, FarEndReferenceCapability.AVAILABLE_PCM, 24_000, true, true, true, false, false, false, false, false)
        assertNull(DuplexEligibility.denial(ok))
        assertEquals(DuplexDenial.FEATURE_DISABLED, DuplexEligibility.denial(ok.copy(featureEnabled = false)))
        assertEquals(DuplexDenial.NOT_SPEAKING, DuplexEligibility.denial(ok.copy(jarvisSpeaking = false)))
        assertEquals(DuplexDenial.FAR_END_UNAVAILABLE, DuplexEligibility.denial(ok.copy(farEnd = FarEndReferenceCapability.UNAVAILABLE_PLATFORM_TTS)))
        assertEquals(DuplexDenial.FAR_RATE_UNSUPPORTED, DuplexEligibility.denial(ok.copy(farRateHz = 22_050)))
        assertEquals(DuplexDenial.AEC_NOT_READY, DuplexEligibility.denial(ok.copy(aecReady = false)))
        assertEquals(DuplexDenial.VAD_NOT_READY, DuplexEligibility.denial(ok.copy(vadReady = false)))
        assertEquals(DuplexDenial.PERMISSION_DENIED, DuplexEligibility.denial(ok.copy(micPermissionGranted = false)))
        assertEquals(DuplexDenial.TRANSLATOR_ACTIVE, DuplexEligibility.denial(ok.copy(translatorActive = true)))
        assertEquals(DuplexDenial.RECOGNIZER_ACTIVE, DuplexEligibility.denial(ok.copy(recognizerActive = true)))
        assertEquals(DuplexDenial.WAKE_WORD_ACTIVE, DuplexEligibility.denial(ok.copy(wakeWordListening = true)))
        assertEquals(DuplexDenial.CAPTURE_BUSY, DuplexEligibility.denial(ok.copy(captureBusy = true)))
        assertEquals(DuplexDenial.ALREADY_RUNNING, DuplexEligibility.denial(ok.copy(monitorRunning = true)))
    }
}
