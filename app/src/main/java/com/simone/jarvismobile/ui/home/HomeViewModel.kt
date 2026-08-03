package com.simone.jarvismobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.AudioRouteManager
import com.simone.jarvismobile.audio.AudioRouteState
import com.simone.jarvismobile.audio.ListeningService
import com.simone.jarvismobile.audio.TextToSpeechEngine
import com.simone.jarvismobile.core.state.ConversationEvent
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.core.state.ConversationStateMachine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Fase 1 audio loop:
 *   press → prepare audio (route to AirPods when present) → short "listening"
 *   window → speak a fixed Italian reply through the ACTUAL selected device.
 *
 * The conversation flow is governed by the pure-Kotlin [ConversationStateMachine]
 * from :core, keeping this ViewModel a thin Android adapter (docs §5/§6). Phases
 * 2–4 replace the fixed reply with real STT + LLM + streaming TTS.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val audioRouteManager: AudioRouteManager,
    private val tts: TextToSpeechEngine,
) : AndroidViewModel(application) {

    private val machine = ConversationStateMachine()

    val state: StateFlow<ConversationState> = machine.state

    val routeState: StateFlow<AudioRouteState> = audioRouteManager.routeState

    val ttsState = tts.state

    /** Fase 1 entry point: begins an explicit, user-initiated session. */
    fun onTalkPressed() {
        val ctx = getApplication<Application>()
        ListeningService.start(ctx)
        viewModelScope.launch { runFase1Loop() }
    }

    fun onCancel() {
        machine.dispatch(ConversationEvent.CancelRequested)
        tts.stop()
        ListeningService.stop(getApplication())
    }

    private suspend fun runFase1Loop() {
        machine.dispatch(ConversationEvent.StartRequested) // -> PreparingAudio
        val input = runCatching { audioRouteManager.beginSession(preferBluetooth = true) }.getOrNull()
        if (input == null) {
            machine.dispatch(ConversationEvent.RecoverableFailure("audio_begin_failed"))
            return
        }
        machine.dispatch(ConversationEvent.AudioReady)  // -> Listening

        // Fase 1 has no VAD yet: a short fixed listening window stands in.
        delay(LISTEN_WINDOW_MS)
        machine.dispatch(ConversationEvent.SpeechEnded)      // -> FinalizingSpeech
        machine.dispatch(ConversationEvent.SpeechEnded)      // -> Transcribing
        machine.dispatch(ConversationEvent.TranscriptReady(FASE1_FIXED_TRANSCRIPT))
        machine.dispatch(ConversationEvent.MemoryRetrieved)  // -> Routing
        machine.dispatch(ConversationEvent.Routed(com.simone.jarvismobile.core.state.RouteTarget.LOCAL))
        machine.dispatch(ConversationEvent.AnswerReady)      // -> Speaking

        if (tts.ensureReady()) {
            tts.speak(FASE1_FIXED_REPLY)
        } else {
            machine.dispatch(ConversationEvent.RecoverableFailure("tts_unavailable"))
        }

        machine.dispatch(ConversationEvent.SpeechSynthesisFinished) // -> FollowUpWindow
        delay(FOLLOW_UP_MS)
        machine.dispatch(ConversationEvent.FollowUpTimeout)         // -> Idle
        audioRouteManager.endSession()
        ListeningService.stop(getApplication())
    }

    override fun onCleared() {
        tts.shutdown()
        audioRouteManager.endSession()
        super.onCleared()
    }

    companion object {
        private const val LISTEN_WINDOW_MS = 1_500L
        private const val FOLLOW_UP_MS = 8_000L
        private const val FASE1_FIXED_TRANSCRIPT = "(fase 1: nessuna trascrizione)"
        private const val FASE1_FIXED_REPLY =
            "Sono JARVIS. L'audio è instradato correttamente. " +
                "Il riconoscimento vocale arriverà nella prossima fase."
    }
}
