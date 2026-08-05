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
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.tools.CommandMatcher
import com.simone.jarvismobile.tools.ItalianNumbers
import com.simone.jarvismobile.tools.LlmIntentClassifier
import com.simone.jarvismobile.tools.Match
import com.simone.jarvismobile.tools.ToolOutcome
import com.simone.jarvismobile.tools.ToolRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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
    private val memory: MemoryIndex,
    private val tools: ToolRunner,
    private val intentClassifier: LlmIntentClassifier,
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

    /** True while a typed (written-chat) message is being answered. */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val sessionMutex = Mutex()

    /** A tool call awaiting the user's spoken/typed confirmation, if any. */
    @Volatile private var pendingConfirmation: com.simone.jarvismobile.core.protocol.ToolCall? = null

    /** A tool JARVIS asked a follow-up question about, with what it already knows. */
    private data class Pending(val tool: String, val missing: String, val args: Map<String, String>)

    @Volatile private var pendingSlot: Pending? = null

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
        // A pending confirmation is answered by this utterance (yes / no).
        pendingConfirmation?.let { call ->
            val answer = transcript.lowercase().trim().trim('.', '!', ',')
            val firstWord = answer.substringBefore(' ')
            if (firstWord in CONFIRM_WORDS || CONFIRM_WORDS.any { answer.startsWith(it) }) {
                pendingConfirmation = null
                _diagnostic.value = "tool confermato: ${call.name}"
                return when (val outcome = tools.run(call, confirmed = true)) {
                    is ToolOutcome.Done -> outcome.spoken
                    is ToolOutcome.Failed -> outcome.spoken
                    is ToolOutcome.NeedsConfirmation -> "Non posso eseguirlo senza conferma."
                }
            }
            if (firstWord in DECLINE_WORDS) {
                pendingConfirmation = null
                return "Va bene, non lo faccio."
            }
            // Neither yes nor no: drop the pending action and treat this as a
            // brand-new request rather than silently executing something.
            pendingConfirmation = null
        }

        // The user is answering a question JARVIS asked ("Per quanto tempo?").
        pendingSlot?.let { pending ->
            val toolName = pending.tool
            pendingSlot = null
            fillSlot(pending, transcript)?.let { completed ->
                _diagnostic.value = "tool completato: $toolName"
                return when (val outcome = tools.run(completed)) {
                    is ToolOutcome.Done -> outcome.spoken
                    is ToolOutcome.Failed -> outcome.spoken
                    is ToolOutcome.NeedsConfirmation -> {
                        pendingConfirmation = outcome.call
                        outcome.prompt
                    }
                }
            }
            // Couldn't read an answer out of it — fall through and treat the
            // utterance as a fresh request rather than guessing.
        }

        // Phase 6 — understanding, in two stages.
        //
        // 1) The local AI analyses the request against the tool list, so a
        //    command is understood however it is phrased. It can only name a
        //    tool that already exists in the registry, and every argument is
        //    re-validated, so understanding grows but privileges don't.
        when (val aiMatch = runCatching { intentClassifier.classify(transcript) }.getOrNull()) {
            is Match.Ask -> {
                pendingSlot = Pending(aiMatch.tool, aiMatch.missing, aiMatch.partial)
                _diagnostic.value = "chiedo (ai): ${aiMatch.missing}"
                return aiMatch.question
            }
            is Match.Run -> {
                _diagnostic.value = "tool (ai): ${aiMatch.call.name}"
                return when (val outcome = tools.run(aiMatch.call)) {
                    is ToolOutcome.Done -> outcome.spoken
                    is ToolOutcome.Failed -> outcome.spoken
                    is ToolOutcome.NeedsConfirmation -> {
                        pendingConfirmation = outcome.call
                        outcome.prompt
                    }
                }
            }
            null -> Unit
        }

        // 2) Explicit patterns as the safety net: they catch what the model
        //    missed, fill in a missing detail by asking, and keep commands
        //    working when no model is loaded at all.
        when (val match = CommandMatcher.match(transcript)) {
            is Match.Ask -> {
                // Remember what we were doing so the next reply completes it.
                pendingSlot = Pending(match.tool, match.missing, match.partial)
                _diagnostic.value = "chiedo: ${match.missing}"
                return match.question
            }
            is Match.Run -> {
                _diagnostic.value = "tool: ${match.call.name}"
                return when (val outcome = tools.run(match.call)) {
                    is ToolOutcome.Done -> outcome.spoken
                    is ToolOutcome.Failed -> outcome.spoken
                    is ToolOutcome.NeedsConfirmation -> {
                        pendingConfirmation = outcome.call
                        outcome.prompt
                    }
                }
            }
            null -> Unit // not a command → normal conversation below
        }
        if (llm.loadState.value != LlmLoadState.LOADED) {
            return "Ho capito: $transcript. Carica un modello nella schermata Modelli per risposte vere."
        }
        // Talk to the model like a chatbot: the user's words go through VERBATIM.
        // Retrieved notes belong in the system instruction, not wrapped around the
        // question — a templated "Contesto … Domanda:" block invites a small model
        // to CONTINUE the template instead of answering it.
        val retrieved = runCatching { memory.retrieve(transcript, MEMORY_TOP_K) }.getOrDefault(emptyList())
        val system = buildString {
            append(systemPrompt.trim())
            if (retrieved.isNotEmpty()) {
                append("\n\nAPPUNTI DI SIMONE (usali quando sono pertinenti, senza citarli se non serve):\n")
                retrieved.forEach { r ->
                    append("- ").append(r.chunk.text.replace('\n', ' ').trim().take(600)).append('\n')
                }
            }
        }
        _diagnostic.value = if (retrieved.isEmpty()) {
            "thinking (llm ${loadedModelName.value})"
        } else {
            "memoria: ${retrieved.size} frammenti · llm ${loadedModelName.value}"
        }
        llm.chat(transcript, system)?.trim()?.ifBlank { null }?.let { return it }

        // Generation failed. The usual cause is an exhausted/!corrupted KV cache
        // after a long chat, which a fresh conversation clears — so retry once
        // (losing the in-session history, not the vault memory) before giving up.
        Log.w(TAG, "llm_chat_null_retrying_fresh")
        _diagnostic.value = "riprovo con conversazione nuova"
        llm.resetConversation()
        llm.chat(transcript, system)?.trim()?.ifBlank { null }?.let { return it }

        return "Il modello non è riuscito a rispondere. Prova «Nuova conversazione», " +
            "o ricarica il modello dalla schermata Modelli."
    }

    /**
     * Starts a fresh conversation: the model forgets the previous chat. The
     * multi-turn memory otherwise persists across presses while the model stays
     * loaded, so context builds up naturally between turns.
     */
    /**
     * Written-chat entry point: answer a TYPED message (no mic, no TTS). Shares the
     * same reply pipeline as voice — memory retrieval, multi-turn context, and the
     * "ricorda …" command all work — and appends both sides to the on-screen log.
     */
    suspend fun sendText(text: String) {
        val message = text.trim()
        if (message.isBlank() || sessionMutex.isLocked) return
        sessionMutex.withLock {
            _lastError.value = null
            _sending.value = true
            try {
                appendMessage(fromUser = true, text = message)
                _transcript.value = message
                _diagnostic.value = "chat scritta"
                val answer = generateAnswer(message)
                _reply.value = answer
                appendMessage(fromUser = false, text = answer)
            } catch (e: Exception) {
                _lastError.value = "text_crash_${e.javaClass.simpleName}"
                _diagnostic.value = "CRASH ${e.javaClass.simpleName}"
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Completes a tool call from the user's answer to JARVIS's own question
     * ("Per quanto tempo?" → "dieci minuti"). Returns null when the reply
     * doesn't contain the missing detail, so we never guess.
     */
    private fun fillSlot(
        pending: Pending,
        answer: String,
    ): com.simone.jarvismobile.core.protocol.ToolCall? {
        fun build(vararg args: Pair<String, String>) =
            com.simone.jarvismobile.core.protocol.ToolCall(
                id = java.util.UUID.randomUUID().toString(),
                name = pending.tool,
                arguments = kotlinx.serialization.json.JsonObject(
                    args.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) },
                ),
                requiresConfirmation = false,
            )

        val reply = answer.trim()
        return when (pending.tool) {
            // A bare number here can only be a duration, so accept it as minutes.
            "set_timer" -> ItalianNumbers.duration(reply, allowBareNumber = true)
                ?.let { build("seconds" to it.toString()) }

            "set_alarm" -> ItalianNumbers.clockTime(reply)
                ?.let { (h, m) -> build("hour" to h.toString(), "minute" to m.toString()) }

            "remember" -> when (pending.missing) {
                // We asked WHEN: attach it to the note we already have.
                "when" -> {
                    val note = pending.args["text"].orEmpty()
                    if (note.isBlank() || reply.length < 2) {
                        null
                    } else {
                        build("text" to "$note — $reply")
                    }
                }
                // We asked WHAT: the whole reply is the note.
                else -> reply.takeIf { it.length >= 3 }?.let { build("text" to it) }
            }

            else -> null
        }
    }

    /** Builds the vault memory index in the background if a vault is configured. */
    suspend fun ensureMemoryReady() = memory.ensureBuilt()

    /**
     * Auto-loads the last-used model at startup so the user does not have to press
     * "Carica" after every app restart. The model can't literally survive process
     * death (Android reclaims RAM), so "keeping" it means transparently reloading
     * the persisted model file on launch. No-op if already loaded/loading, if no
     * model was chosen, or if the file is gone.
     */
    suspend fun ensureModelReady() {
        val current = llm.loadState.value
        if (current == LlmLoadState.LOADED || current == LlmLoadState.LOADING) return
        val path = settings.modelPath.first()
        if (path.isBlank()) return
        val file = File(path)
        if (!file.exists()) return
        val name = settings.modelName.first().ifBlank { file.name }
        _diagnostic.value = "carico modello…"
        llm.load(path, name)
    }

    fun newConversation() {
        llm.resetConversation()
        pendingConfirmation = null
        pendingSlot = null
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

        /** How many vault chunks to inject as grounding context per question. */
        const val MEMORY_TOP_K = 4

        /** Affirmative replies that approve a pending tool confirmation. */
        private val CONFIRM_WORDS = listOf("sì", "si", "certo", "conferma", "ok", "va bene", "procedi", "yes")

        /** Replies that cancel a pending tool confirmation. */
        private val DECLINE_WORDS = listOf("no", "annulla", "lascia", "niente", "ferma", "stop")
        private const val TAG = "JarvisSession"
    }
}
