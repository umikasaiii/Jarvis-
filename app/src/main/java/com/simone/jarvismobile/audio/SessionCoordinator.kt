package com.simone.jarvismobile.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.core.state.ConversationEvent
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.core.state.ConversationStateMachine
import com.simone.jarvismobile.core.state.RouteTarget
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns a single conversation turn and is the single source of truth the UI
 * observes. Phase 2 wires real offline speech-to-text: listen → transcribe →
 * (Phase-2 echo reply) → speak, driving the shared [ConversationStateMachine].
 *
 * The capture path stays minimal (the recognizer opens its own mic); no
 * foreground service and no audio-focus/communication-mode juggling on the
 * phone-only path — that was what blocked the mic on MagicOS. Audio is never
 * persisted; logs are technical and redacted.
 */
@Singleton
class SessionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioRouteManager: AudioRouteManager,
    private val audioCapture: AudioCapture,
    private val stt: SpeechToTextEngine,
    private val tts: TextToSpeechEngine,
    private val llm: LlmEngine,
) {

    private val machine = ConversationStateMachine()
    val state: StateFlow<ConversationState> = machine.state

    val llmLoadState: StateFlow<LlmLoadState> = llm.loadState
    val loadedModelName: StateFlow<String?> = llm.loadedModelName

    private val systemPrompt: String by lazy {
        runCatching {
            context.assets.open("prompts/jarvis_system_it.md").bufferedReader().use { it.readText() }
        }.getOrDefault("Sei JARVIS, un assistente personale offline. Rispondi in italiano, breve e naturale.")
    }

    val micLevel: StateFlow<Float> = audioCapture.micLevel
    val routeState: StateFlow<AudioRouteState> = audioRouteManager.routeState
    val ttsState: StateFlow<TtsState> = tts.state
    val selectedVoiceName: StateFlow<String?> = tts.selectedVoiceName
    val partialTranscript: StateFlow<String> = stt.partial

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _reply = MutableStateFlow("")
    val reply: StateFlow<String> = _reply.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _diagnostic = MutableStateFlow("")
    val diagnostic: StateFlow<String> = _diagnostic.asStateFlow()

    private val sessionMutex = Mutex()

    /** Runs one conversation turn. Safe to call repeatedly; ignores overlap. */
    suspend fun runSession() {
        if (sessionMutex.isLocked) {
            Log.i(TAG, "session_skip already_running")
            return
        }
        sessionMutex.withLock {
            _lastError.value = null
            _transcript.value = ""
            _reply.value = ""
            _diagnostic.value = "start"
            try {
                runTurn()
            } catch (e: Exception) {
                _lastError.value = "crash_${e.javaClass.simpleName}"
                _diagnostic.value = "CRASH ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "session_crash ${e.javaClass.simpleName}")
                machine.dispatch(ConversationEvent.RecoverableFailure("crash"))
            }
        }
    }

    private suspend fun runTurn() {
        machine.dispatch(ConversationEvent.StartRequested) // -> PreparingAudio
        if (!hasRecordPermission()) {
            _diagnostic.value = "no_mic_permission"
            machine.dispatch(ConversationEvent.PermissionDenied)
            return
        }
        machine.dispatch(ConversationEvent.AudioReady) // -> Listening
        _diagnostic.value = "listening (stt)"

        when (val result = stt.transcribe("it-IT")) {
            is SttResult.Text -> {
                _transcript.value = result.text
                _diagnostic.value = "heard: ${result.text.take(40)}"
                machine.dispatch(ConversationEvent.SpeechEnded)   // -> FinalizingSpeech
                machine.dispatch(ConversationEvent.SpeechEnded)   // -> Transcribing
                machine.dispatch(ConversationEvent.TranscriptReady(result.text)) // -> RetrievingMemory
                machine.dispatch(ConversationEvent.MemoryRetrieved) // -> Routing
                machine.dispatch(ConversationEvent.Routed(RouteTarget.LOCAL)) // -> ThinkingLocal
                val answer = generateAnswer(result.text)
                _reply.value = answer
                machine.dispatch(ConversationEvent.AnswerReady) // -> Speaking
                speakOut(answer)
                machine.dispatch(ConversationEvent.SpeechSynthesisFinished) // -> FollowUpWindow
                machine.dispatch(ConversationEvent.FollowUpTimeout) // -> Idle
            }

            SttResult.NoSpeech -> {
                _diagnostic.value = "no_speech"
                machine.dispatch(ConversationEvent.SpeechEnded)
                machine.dispatch(ConversationEvent.SpeechEnded)
                machine.dispatch(ConversationEvent.TranscriptReady("")) // -> RecoverableError(empty_transcript)
                _lastError.value = "empty_transcript"
                speakOut("Non ho sentito nulla. Riprova.")
            }

            is SttResult.Unavailable -> {
                _diagnostic.value = "stt_unavailable: ${result.reason}"
                _lastError.value = "stt_unavailable"
                machine.dispatch(ConversationEvent.RecoverableFailure("stt_unavailable"))
                speakOut(
                    "Il riconoscimento vocale offline non è disponibile su questo telefono. " +
                        "Nella prossima fase userò un motore incluso nell'app.",
                )
            }

            is SttResult.Failure -> {
                _diagnostic.value = "stt_fail: ${result.code}"
                _lastError.value = result.code
                machine.dispatch(ConversationEvent.RecoverableFailure(result.code))
            }
        }
    }

    /**
     * Generates the reply. When a local model is loaded it answers for real
     * (Phase 3); otherwise it falls back to the Phase-2 echo and points the user
     * to the Models screen.
     */
    private suspend fun generateAnswer(transcript: String): String {
        if (llm.loadState.value != LlmLoadState.LOADED) {
            return "Ho capito: $transcript. Carica un modello nella schermata Modelli per risposte vere."
        }
        _diagnostic.value = "thinking (llm ${loadedModelName.value})"
        val prompt = buildString {
            append(systemPrompt.trim())
            append("\n\nUtente: ").append(transcript)
            append("\nJARVIS:")
        }
        return llm.generate(prompt)?.trim()?.ifBlank { null }
            ?: "Non sono riuscito a generare una risposta con il modello."
    }

    private suspend fun speakOut(text: String) {
        if (tts.ensureReady()) {
            tts.speak(text)
        } else {
            _diagnostic.value = "${_diagnostic.value} | tts_unavailable [${tts.lastDetail.value}]"
        }
    }

    fun cancel() {
        stt.cancel()
        audioCapture.cancel()
        tts.stop()
        machine.dispatch(ConversationEvent.CancelRequested)
    }

    // --- Diagnostics helpers --------------------------------------------

    fun sttAvailable(): Boolean = stt.isAvailable()

    /** Diagnostics: run one recognition in isolation. */
    suspend fun testStt(): SttResult = stt.transcribe("it-IT")

    /** Diagnostics: record a fixed window only, to exercise the mic + level meter. */
    suspend fun testMicrophone(): CaptureResult {
        val result = audioCapture.capture(DEFAULT_RECORD_MS)
        if (result != CaptureResult.COMPLETED) _lastError.value = "mic_test_${result.name.lowercase()}"
        return result
    }

    /** Diagnostics: speak the fixed phrase to exercise offline TTS routing. */
    suspend fun testVoice(): Boolean {
        if (!tts.ensureReady()) { _lastError.value = "tts_unavailable"; return false }
        tts.speak(FIXED_REPLY)
        return true
    }

    fun resetAudio() {
        stt.cancel()
        audioCapture.cancel()
        tts.stop()
        audioRouteManager.endSession()
        machine.dispatch(ConversationEvent.Reset)
        _lastError.value = null
        _diagnostic.value = ""
    }

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val DEFAULT_RECORD_MS = 3_000L
        const val FIXED_REPLY = "Sistema audio operativo. Sono pronto."
        private const val TAG = "JarvisSession"
    }
}
