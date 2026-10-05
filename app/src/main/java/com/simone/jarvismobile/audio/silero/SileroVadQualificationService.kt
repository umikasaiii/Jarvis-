package com.simone.jarvismobile.audio.silero

import com.simone.jarvismobile.audio.AudioCapture
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.audio.WakeWordController
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.core.voice.VoiceCaptureFailure
import com.simone.jarvismobile.core.voice.VoiceCaptureState
import com.simone.jarvismobile.core.voice.silero.SileroQualificationAvailability
import com.simone.jarvismobile.core.voice.silero.SileroQualificationGate
import com.simone.jarvismobile.core.voice.silero.SileroQualificationResult
import com.simone.jarvismobile.core.voice.silero.SileroModelState
import com.simone.jarvismobile.core.voice.silero.SileroQualificationSession
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.translate.LiveTranslatorManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SileroQualificationState {
    data object Idle : SileroQualificationState
    data object Running : SileroQualificationState
    data class Unavailable(val reason: SileroQualificationAvailability) : SileroQualificationState
    data class Done(val result: SileroQualificationResult) : SileroQualificationState
}

/**
 * Manual, diagnostics-only Silero VAD qualification (Live Voice Phase 0.8). Runs a fixed short window on the
 * canonical [AudioCapture] PCM stream, but ONLY if the microphone is provably free (no SpeechRecognizer
 * conversation / wake-word listen / translator / other capture). It never feeds production voice: no STT,
 * TTS, state-machine or barge-in effect — VAD events are only counted.
 */
@Singleton
class SileroVadQualificationService @Inject constructor(
    private val manager: SileroVadModelManager,
    private val audioCapture: AudioCapture,
    private val coordinator: SessionCoordinator,
    private val wakeWord: WakeWordController,
    private val translator: LiveTranslatorManager,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow<SileroQualificationState>(SileroQualificationState.Idle)
    val state: StateFlow<SileroQualificationState> = _state.asStateFlow()

    suspend fun availability(): SileroQualificationAvailability {
        val wakeEnabled = runCatching { settings.wakeWordEnabled.first() }.getOrDefault(true) // unknown => assume enabled
        return SileroQualificationGate.check(
            modelReady = manager.status.value.state == SileroModelState.READY,
            conversationIdle = coordinator.state.value == ConversationState.Idle,
            wakeWordEnabled = wakeEnabled,
            wakeWordListening = wakeWord.listeningForWake.value,
            translatorActive = translator.isRunning,
            captureActive = audioCapture.captureSnapshot.value.state != VoiceCaptureState.IDLE,
        )
    }

    suspend fun run(durationMs: Long = DEFAULT_DURATION_MS) {
        if (_state.value == SileroQualificationState.Running) return
        manager.refresh()
        val gate = availability()
        if (gate != SileroQualificationAvailability.AVAILABLE) {
            _state.value = SileroQualificationState.Unavailable(gate)
            return
        }
        val engine = manager.ensureLoaded()
        if (engine == null) {
            _state.value = SileroQualificationState.Unavailable(SileroQualificationAvailability.MODEL_NOT_READY)
            return
        }
        _state.value = SileroQualificationState.Running
        val session = SileroQualificationSession(engine) // resets engine + policy: nothing leaks from earlier runs
        var failure: VoiceCaptureFailure? = null
        try {
            coroutineScope {
                // Subscribe BEFORE capture starts (the frame flow has replay = 0).
                val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    audioCapture.frames.collect { session.onFrame(it) }
                }
                val capture = async { audioCapture.captureContinuous() }
                delay(durationMs.coerceIn(1_000L, MAX_DURATION_MS))
                audioCapture.cancel()
                failure = capture.await().failure?.takeIf { it != VoiceCaptureFailure.CANCELLED } // our own stop is normal
                collector.cancel()
            }
        } finally {
            audioCapture.cancel() // never leave the microphone open if this job is cancelled
            if (_state.value == SileroQualificationState.Running) _state.value = SileroQualificationState.Idle
        }
        _state.value = SileroQualificationState.Done(
            session.result(audioCapture.captureSnapshot.value.framesDropped, failure?.name),
        )
    }

    companion object {
        const val DEFAULT_DURATION_MS = 10_000L
        const val MAX_DURATION_MS = 60_000L
    }
}
