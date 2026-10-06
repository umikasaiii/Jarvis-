package com.simone.jarvismobile.audio.duplex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.audio.AudioCapture
import com.simone.jarvismobile.audio.TextToSpeechEngine
import com.simone.jarvismobile.audio.WakeWordController
import com.simone.jarvismobile.audio.silero.SileroVadModelManager
import com.simone.jarvismobile.core.voice.AecDuplexPipeline
import com.simone.jarvismobile.core.voice.AecPipelineSnapshot
import com.simone.jarvismobile.core.voice.DuplexCapturePort
import com.simone.jarvismobile.core.voice.DuplexConditions
import com.simone.jarvismobile.core.voice.DuplexDenial
import com.simone.jarvismobile.core.voice.DuplexEligibility
import com.simone.jarvismobile.core.voice.DuplexReleaseResult
import com.simone.jarvismobile.core.voice.FarEndPcmFrame
import com.simone.jarvismobile.core.voice.FarEndReferenceCapability
import com.simone.jarvismobile.core.voice.LiveDuplexMonitor
import com.simone.jarvismobile.core.voice.PcmCaptureMode
import com.simone.jarvismobile.core.voice.VadTurnConfig
import com.simone.jarvismobile.core.voice.VadTurnPolicy
import com.simone.jarvismobile.core.voice.VoiceCaptureOutcome
import com.simone.jarvismobile.core.voice.VoicePcmFrame
import com.simone.jarvismobile.core.voice.VoiceCaptureState
import com.simone.jarvismobile.core.voice.EchoCancellerFailure
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.translate.LiveTranslatorManager
import com.simone.jarvismobile.voice.aec.EchoCancellerCreation
import com.simone.jarvismobile.voice.aec.WebRtcEchoCanceller
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded, content-free outcome of the last duplex attempt (diagnostics only). */
data class DuplexRunDiagnostic(
    val denial: DuplexDenial? = null,
    val aecFailure: EchoCancellerFailure? = null,
    val farRateHz: Int = 0,
    val ran: Boolean = false,
    val confirmed: Boolean = false,
    val releaseResult: DuplexReleaseResult? = null,
    /** Confirmed speech -> AudioRecord released (ms); null when no interruption. */
    val handoffReleaseMs: Long? = null,
    val aec: AecPipelineSnapshot? = null,
    val vadFrames: Long = 0,
    val vadAvgMs: Double? = null,
    val vadMaxMs: Double? = null,
    val captureFramesDropped: Long = 0,
    val captureDetail: String = "",
)

/**
 * LV-R1 — the app-side owner of ONE acoustic-interruption attempt per utterance. It builds the
 * [LiveDuplexMonitor] over the canonical capture owner (single AudioRecord, ECHO_CONTROLLED_RAW mode),
 * the real far-end PCM of PcmPlayer, a fresh WebRTC AEC3 engine and the existing Silero VAD, and
 * enforces the handoff rule: on confirmed speech the AudioRecord is stopped AND released BEFORE
 * [onConfirmed] runs (which stops TTS and lets the platform SpeechRecognizer start).
 *
 * Never throws into the speaking path: every failure just means "no automatic interruption" and TTS
 * keeps playing exactly as before. Default OFF ([SettingsRepository.acousticBargeInEnabled]).
 */
@Singleton
class DuplexMonitorController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val audioCapture: AudioCapture,
    private val silero: SileroVadModelManager,
    private val wakeWord: Lazy<WakeWordController>,
    private val translator: Lazy<LiveTranslatorManager>,
) {
    private val _last = MutableStateFlow(DuplexRunDiagnostic())
    val last: StateFlow<DuplexRunDiagnostic> = _last.asStateFlow()
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Runs for the duration of one TTS utterance (the caller cancels it when speaking ends). Returns only
     * after the microphone is released. [onConfirmed] is invoked at most once, only after a verified release.
     */
    suspend fun runDuringSpeech(tts: TextToSpeechEngine, onConfirmed: suspend () -> Unit) {
        if (!running.compareAndSet(false, true)) { _last.value = DuplexRunDiagnostic(denial = DuplexDenial.ALREADY_RUNNING); return }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var monitor: LiveDuplexMonitor? = null
        try {
            val enabled = runCatching { settings.acousticBargeInEnabled.first() }.getOrDefault(false)
            if (!enabled) { _last.value = DuplexRunDiagnostic(denial = DuplexDenial.FEATURE_DISABLED); return }

            // 1) Wait (bounded) until the far-end PCM path is genuinely live, or platform TTS is speaking.
            var firstFar: FarEndPcmFrame? = null
            var capability = tts.farEndReferenceCapability
            val waited = withTimeoutOrNull(FAR_END_WAIT_MS) {
                var decided = false
                while (!decided) {
                    capability = tts.farEndReferenceCapability
                    when (capability) {
                        FarEndReferenceCapability.UNAVAILABLE_PLATFORM_TTS -> decided = true
                        FarEndReferenceCapability.AVAILABLE_PCM -> {
                            firstFar = withTimeoutOrNull(FIRST_FRAME_WAIT_MS) { tts.farEndFrames.first() }
                            decided = true
                        }
                        else -> delay(POLL_MS)
                    }
                }
                true
            }
            val far = firstFar
            if (waited == null || capability != FarEndReferenceCapability.AVAILABLE_PCM || far == null) {
                _last.value = DuplexRunDiagnostic(denial = DuplexDenial.FAR_END_UNAVAILABLE)
                return
            }
            val farRate = far.sampleRateHz
            if (!com.simone.jarvismobile.core.voice.EchoRatePolicy.farRateSupported(farRate)) {
                _last.value = DuplexRunDiagnostic(denial = DuplexDenial.FAR_RATE_UNSUPPORTED, farRateHz = farRate)
                return
            }

            // 2) AEC3 + Silero must both be ready.
            val creation = WebRtcEchoCanceller.create(farRate)
            val canceller = (creation as? EchoCancellerCreation.Ready)?.canceller
            val engine = if (canceller != null) runCatching { silero.ensureLoaded() }.getOrNull() else null
            val conditions = DuplexConditions(
                featureEnabled = true,
                jarvisSpeaking = true,
                farEnd = capability,
                farRateHz = farRate,
                aecReady = canceller != null,
                vadReady = engine != null,
                micPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                translatorActive = translator.get().isRunning,
                recognizerActive = false, // the turn's STT has finished before speech starts; see SessionCoordinator.speakOut
                wakeWordListening = wakeWord.get().listeningForWake.value,
                captureBusy = audioCapture.captureSnapshot.value.state != VoiceCaptureState.IDLE,
                monitorRunning = false,
            )
            val denial = DuplexEligibility.denial(conditions)
            if (denial != null || canceller == null || engine == null) {
                canceller?.close()
                _last.value = DuplexRunDiagnostic(
                    denial = denial, farRateHz = farRate,
                    aecFailure = (creation as? EchoCancellerCreation.Unavailable)?.failure,
                )
                return
            }

            // 3) Run the monitor over the SINGLE canonical capture owner.
            val pipeline = AecDuplexPipeline(canceller)
            pipeline.onFarEnd(far)
            val port = object : DuplexCapturePort {
                override val frames: SharedFlow<VoicePcmFrame> = audioCapture.frames
                override suspend fun runUntilCancelled(): VoiceCaptureOutcome =
                    audioCapture.captureContinuous(PcmCaptureMode.ECHO_CONTROLLED_RAW)
                override fun cancel() = audioCapture.cancel()
            }
            val m = LiveDuplexMonitor(port, tts.farEndFrames, pipeline, engine, VadTurnPolicy(VadTurnConfig.PLACEHOLDER_UNQUALIFIED), scope)
            monitor = m
            // Subscribe BEFORE start(): the events flow has replay = 0 and drops values without subscribers.
            val confirmedEvent = scope.async(start = CoroutineStart.UNDISPATCHED) { m.events.first() }
            m.start()
            _last.value = DuplexRunDiagnostic(ran = true, farRateHz = farRate)
            confirmedEvent.await() // suspends until confirmed speech, or is cancelled when the caller stops speaking
            val t0 = System.nanoTime()
            val release = withContext(NonCancellable) { m.stopAndAwaitRelease() }
            val handoffMs = (System.nanoTime() - t0) / 1_000_000L
            monitor = null
            publish(m, engine, farRate, confirmed = true, release = release, handoffMs = handoffMs)
            if (release == DuplexReleaseResult.RELEASED) onConfirmed()
            // On TIMEOUT: AudioRecord could not be proven released -> do NOT start STT; TTS keeps playing.
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never let an unexpected failure reach the speaking path: no automatic interruption, TTS goes on.
            _last.value = DuplexRunDiagnostic(captureDetail = "error ${e.javaClass.simpleName}")
        } finally {
            val m = monitor
            if (m != null) {
                val release = withContext(NonCancellable) { m.stopAndAwaitRelease() }
                publish(m, null, _last.value.farRateHz, confirmed = false, release = release, handoffMs = null)
            }
            scope.cancel()
            running.set(false)
        }
    }

    private fun publish(m: LiveDuplexMonitor, engine: com.simone.jarvismobile.core.voice.silero.SileroVadEngine?, farRate: Int, confirmed: Boolean, release: DuplexReleaseResult, handoffMs: Long?) {
        val snap = m.snapshot.value
        _last.value = DuplexRunDiagnostic(
            ran = true, confirmed = confirmed, farRateHz = farRate, releaseResult = release, handoffReleaseMs = handoffMs,
            aec = snap.aec, vadFrames = snap.vadFrames,
            captureFramesDropped = audioCapture.captureSnapshot.value.framesDropped,
            captureDetail = audioCapture.lastDetail.value.take(80),
            vadAvgMs = engine?.stats()?.averageInferenceMs, vadMaxMs = engine?.stats()?.maxInferenceMs,
        )
    }

    private companion object {
        const val FAR_END_WAIT_MS = 5_000L
        const val FIRST_FRAME_WAIT_MS = 1_000L
        const val POLL_MS = 40L
    }
}
