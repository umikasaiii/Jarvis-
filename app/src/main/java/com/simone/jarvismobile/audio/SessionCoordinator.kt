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
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** One line of the on-screen conversation log. */
data class ChatMessage(val fromUser: Boolean, val text: String)

/**
 * Owns the conversation and is the single source of truth the UI observes:
 * listen → transcribe → LLM answer → speak, driving the shared
 * [ConversationStateMachine]. Phase 4 makes it hands-free — after speaking, the
 * mic re-opens for a short follow-up window so the user can reply without
 * pressing again (see [runTurn]); the loop ends on silence or when follow-up is
 * disabled in Settings.
 *
 * The capture path stays minimal (the recognizer opens its own mic); no
 * foreground service and no audio-focus/communication-mode juggling around
 * listening — that was what blocked the mic on MagicOS. Audio focus is held only
 * while speaking (TTS), so music ducks/pauses politely. Audio is never
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
    private val settings: SettingsRepository,
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

    /** On-screen conversation log (mirrors the model's in-session memory). */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private fun appendMessage(fromUser: Boolean, text: String) {
        if (text.isBlank()) return
        _messages.value = _messages.value + ChatMessage(fromUser, text.trim())
    }

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

    /**
     * Runs the conversation as a hands-free loop (Phase 4): listen → answer →
     * speak, then — if follow-up is enabled — re-open the mic for a short window
     * so the user can reply without pressing again. The loop ends on silence, on
     * an error, when follow-up is off, or after [MAX_FOLLOW_UPS] exchanges.
     */
    private suspend fun runTurn() {
        machine.dispatch(ConversationEvent.StartRequested) // -> PreparingAudio
        if (!hasRecordPermission()) {
            _diagnostic.value = "no_mic_permission"
            machine.dispatch(ConversationEvent.PermissionDenied)
            return
        }
        machine.dispatch(ConversationEvent.AudioReady) // -> Listening
        _diagnostic.value = "listening (stt)"

        val followUpEnabled = runCatching { settings.followUpEnabled.first() }.getOrDefault(true)
        var turn = 0
        while (true) {
            val spoke = processTurn(stt.transcribe("it-IT"), isFollowUp = turn > 0)
            if (!spoke) return // a terminal/no-speech outcome was handled inside

            // A reply was spoken; the machine is now in FollowUpWindow.
            if (!followUpEnabled || turn + 1 >= MAX_FOLLOW_UPS) {
                machine.dispatch(ConversationEvent.FollowUpTimeout) // -> Idle
                return
            }
            // Re-open the mic for the follow-up — no re-press needed.
            _diagnostic.value = "follow-up: parla pure…"
            _transcript.value = ""
            machine.dispatch(ConversationEvent.SpeechStarted) // FollowUpWindow -> PreparingAudio
            machine.dispatch(ConversationEvent.AudioReady)    // -> Listening
            turn++
        }
    }

    /**
     * Handles one recognizer result. Returns true when a reply was spoken and the
     * machine is parked in FollowUpWindow (so the caller may re-open the mic);
     * false when the turn reached a terminal/no-speech outcome (already dispatched).
     */
    private suspend fun processTurn(result: SttResult, isFollowUp: Boolean): Boolean =
        when (result) {
            is SttResult.Text -> {
                _transcript.value = result.text
                appendMessage(fromUser = true, text = result.text)
                _diagnostic.value = "heard: ${result.text.take(40)}"
                machine.dispatch(ConversationEvent.SpeechEnded)   // -> FinalizingSpeech
                machine.dispatch(ConversationEvent.SpeechEnded)   // -> Transcribing
                machine.dispatch(ConversationEvent.TranscriptReady(result.text)) // -> RetrievingMemory
                machine.dispatch(ConversationEvent.MemoryRetrieved) // -> Routing
                machine.dispatch(ConversationEvent.Routed(RouteTarget.LOCAL)) // -> ThinkingLocal
                val answer = generateAnswer(result.text)
                _reply.value = answer
                appendMessage(fromUser = false, text = answer)
                machine.dispatch(ConversationEvent.AnswerReady) // -> Speaking
                speakOut(answer)
                machine.dispatch(ConversationEvent.SpeechSynthesisFinished) // -> FollowUpWindow
                true
            }

            SttResult.NoSpeech -> {
                if (isFollowUp) {
                    // Graceful close of the follow-up window: the user simply
                    // didn't continue. Return to Idle quietly, no nagging prompt.
                    _diagnostic.value = "follow-up chiuso (silenzio)"
                    machine.dispatch(ConversationEvent.Reset) // -> Idle
                } else {
                    _diagnostic.value = "no_speech"
                    machine.dispatch(ConversationEvent.SpeechEnded)
                    machine.dispatch(ConversationEvent.SpeechEnded)
                    machine.dispatch(ConversationEvent.TranscriptReady("")) // -> RecoverableError
                    _lastError.value = "empty_transcript"
                    speakOut("Non ho sentito nulla. Riprova.")
                }
                false
            }

            is SttResult.Unavailable -> {
                _diagnostic.value = "stt_unavailable: ${result.reason}"
                _lastError.value = "stt_unavailable"
                machine.dispatch(ConversationEvent.RecoverableFailure("stt_unavailable"))
                speakOut(
                    "Il riconoscimento vocale offline non è disponibile su questo telefono. " +
                        "Nella prossima fase userò un motore incluso nell'app.",
                )
                false
            }

            is SttResult.Failure -> {
                _diagnostic.value = "stt_fail: ${result.code}"
                _lastError.value = result.code
                machine.dispatch(ConversationEvent.RecoverableFailure(result.code))
                false
            }
        }

    /**
     * Generates the reply. When a local model is loaded it answers for real via a
     * multi-turn [LlmEngine.chat] that REMEMBERS the earlier exchanges (the model
     * keeps the conversation history); otherwise it falls back to the echo and
     * points the user to the Models screen.
     */
    private suspend fun generateAnswer(transcript: String): String {
        if (llm.loadState.value != LlmLoadState.LOADED) {
            return "Ho capito: $transcript. Carica un modello nella schermata Modelli per risposte vere."
        }
        _diagnostic.value = "thinking (llm ${loadedModelName.value})"
        return llm.chat(transcript, systemPrompt.trim())?.trim()?.ifBlank { null }
            ?: "Non sono riuscito a generare una risposta con il modello."
    }

    /**
     * Starts a fresh conversation: the model forgets the previous chat. The
     * multi-turn memory otherwise persists across presses while the model stays
     * loaded, so context builds up naturally between turns.
     */
    fun newConversation() {
        llm.resetConversation()
        _messages.value = emptyList()
        _transcript.value = ""
        _reply.value = ""
        _diagnostic.value = "nuova conversazione"
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

        /** Safety cap on consecutive hands-free exchanges before requiring a press. */
        const val MAX_FOLLOW_UPS = 8
        private const val TAG = "JarvisSession"
    }
}
