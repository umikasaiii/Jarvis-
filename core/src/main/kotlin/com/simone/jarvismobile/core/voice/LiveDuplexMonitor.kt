package com.simone.jarvismobile.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class DuplexState { IDLE, STARTING, MONITORING, INTERRUPTING, STOPPING, ERROR }

/** Why the duplex monitor must NOT start (TTS then simply continues, exactly as before). */
enum class DuplexDenial {
    FEATURE_DISABLED, NOT_SPEAKING, FAR_END_UNAVAILABLE, FAR_RATE_UNSUPPORTED, AEC_NOT_READY, VAD_NOT_READY,
    PERMISSION_DENIED, TRANSLATOR_ACTIVE, RECOGNIZER_ACTIVE, WAKE_WORD_ACTIVE, CAPTURE_BUSY, ALREADY_RUNNING,
}

data class DuplexConditions(
    val featureEnabled: Boolean,
    val jarvisSpeaking: Boolean,
    val farEnd: FarEndReferenceCapability,
    val farRateHz: Int,
    val aecReady: Boolean,
    val vadReady: Boolean,
    val micPermissionGranted: Boolean,
    val translatorActive: Boolean,
    val recognizerActive: Boolean,
    val wakeWordListening: Boolean,
    val captureBusy: Boolean,
    val monitorRunning: Boolean,
)

object DuplexEligibility {
    /** First blocking reason, or null when every start condition holds. Pure; deterministic order. */
    fun denial(c: DuplexConditions): DuplexDenial? = when {
        !c.featureEnabled -> DuplexDenial.FEATURE_DISABLED
        c.monitorRunning -> DuplexDenial.ALREADY_RUNNING
        !c.jarvisSpeaking -> DuplexDenial.NOT_SPEAKING
        c.farEnd != FarEndReferenceCapability.AVAILABLE_PCM -> DuplexDenial.FAR_END_UNAVAILABLE
        !EchoRatePolicy.farRateSupported(c.farRateHz) -> DuplexDenial.FAR_RATE_UNSUPPORTED
        !c.aecReady -> DuplexDenial.AEC_NOT_READY
        !c.vadReady -> DuplexDenial.VAD_NOT_READY
        !c.micPermissionGranted -> DuplexDenial.PERMISSION_DENIED
        c.translatorActive -> DuplexDenial.TRANSLATOR_ACTIVE
        c.recognizerActive -> DuplexDenial.RECOGNIZER_ACTIVE
        c.wakeWordListening -> DuplexDenial.WAKE_WORD_ACTIVE
        c.captureBusy -> DuplexDenial.CAPTURE_BUSY
        else -> null
    }
}

/** The single canonical capture owner as seen by the monitor (PcmCaptureEngine in production). */
interface DuplexCapturePort {
    val frames: SharedFlow<VoicePcmFrame>
    /** Runs until cancelled; returns only AFTER the platform recorder has been released. */
    suspend fun runUntilCancelled(): VoiceCaptureOutcome
    fun cancel()
}

enum class DuplexReleaseResult { RELEASED, TIMEOUT, NOT_RUNNING }

/** Emitted at most once per monitor run. Carries no audio and no text. */
class AcousticSpeechConfirmed(val postAecFrameSequence: Long)

data class DuplexMonitorSnapshot(
    val state: DuplexState = DuplexState.IDLE,
    val vadFrames: Long = 0,
    val interruptions: Int = 0,
    val captureFailure: VoiceCaptureFailure? = null,
    val releaseResult: DuplexReleaseResult? = null,
    val aec: AecPipelineSnapshot? = null,
)

/**
 * LV-R1 — THE duplex coordinator. It owns NO recorder and NO player: it only wires the canonical
 * capture port + the far-end frame stream through [AecDuplexPipeline] into Silero + [VadTurnPolicy].
 * Post-AEC audio is the ONLY thing the VAD sees. One run per instance (create a new one per utterance).
 *
 * The monitor never stops TTS or starts STT itself: on confirmed speech it emits one
 * [AcousticSpeechConfirmed]; the owner then calls [stopAndAwaitRelease] and only after RELEASED
 * hands the microphone to the platform recognizer (no AudioRecord/SpeechRecognizer overlap).
 */
class LiveDuplexMonitor(
    private val capture: DuplexCapturePort,
    private val farFrames: SharedFlow<FarEndPcmFrame>,
    private val pipeline: AecDuplexPipeline,
    private val vad: VoiceActivityDetector,
    private val policy: VadTurnPolicy,
    private val scope: CoroutineScope,
    private val releaseTimeoutMs: Long = DEFAULT_RELEASE_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow(DuplexState.IDLE)
    val state: StateFlow<DuplexState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<AcousticSpeechConfirmed>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AcousticSpeechConfirmed> = _events.asSharedFlow()
    private val _snapshot = MutableStateFlow(DuplexMonitorSnapshot())
    val snapshot: StateFlow<DuplexMonitorSnapshot> = _snapshot.asStateFlow()

    private var nearJob: Job? = null
    private var farJob: Job? = null
    private var captureJob: Job? = null
    private var started = false
    @Volatile private var interrupted = false
    private var vadFrames = 0L
    private var captureFailure: VoiceCaptureFailure? = null

    /** Starts monitoring. Subscribes to both frame streams BEFORE capture starts (replay = 0). One shot. */
    @Synchronized
    fun start(): Boolean {
        if (started) return false
        started = true
        _state.value = DuplexState.STARTING
        vad.reset(); policy.reset()
        farJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            farFrames.collect { pipeline.onFarEnd(it) }
        }
        nearJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            capture.frames.collect { frame ->
                for (clean in pipeline.onNearEnd(frame)) {
                    val obs = vad.process(clean)
                    vadFrames++
                    val ev = policy.observe(clean.sequence, obs)
                    if (ev == VadTurnEvent.SPEECH_STARTED && !interrupted) {
                        interrupted = true
                        _state.value = DuplexState.INTERRUPTING
                        publish()
                        _events.tryEmit(AcousticSpeechConfirmed(clean.sequence))
                    }
                }
            }
        }
        captureJob = scope.launch {
            val outcome = capture.runUntilCancelled()
            captureFailure = outcome.failure?.takeIf { it != VoiceCaptureFailure.CANCELLED }
            if (captureFailure != null && _state.value != DuplexState.STOPPING) {
                _state.value = DuplexState.ERROR
                publish()
            }
        }
        if (_state.value == DuplexState.STARTING) _state.value = DuplexState.MONITORING
        publish()
        return true
    }

    /** Cancels capture, waits (bounded) until the platform recorder is released, tears everything down. Idempotent. */
    suspend fun stopAndAwaitRelease(): DuplexReleaseResult {
        if (!started) return DuplexReleaseResult.NOT_RUNNING
        val prior = _state.value
        _state.value = DuplexState.STOPPING
        capture.cancel()
        val job = captureJob
        val released = job == null || job.isCompleted || withTimeoutOrNull(releaseTimeoutMs) { job.join(); true } == true
        nearJob?.cancel(); farJob?.cancel()
        runCatching { vad.reset() }
        pipeline.close()
        val result = if (released) DuplexReleaseResult.RELEASED else DuplexReleaseResult.TIMEOUT
        _state.value = if (released && prior != DuplexState.ERROR) DuplexState.IDLE else DuplexState.ERROR
        _snapshot.value = _snapshot.value.copy(state = _state.value, releaseResult = result, aec = pipeline.snapshot(),
            vadFrames = vadFrames, interruptions = if (interrupted) 1 else 0, captureFailure = captureFailure)
        return result
    }

    private fun publish() {
        _snapshot.value = DuplexMonitorSnapshot(
            state = _state.value, vadFrames = vadFrames, interruptions = if (interrupted) 1 else 0,
            captureFailure = captureFailure, releaseResult = _snapshot.value.releaseResult, aec = pipeline.snapshot(),
        )
    }

    companion object { const val DEFAULT_RELEASE_TIMEOUT_MS = 1_500L }
}
