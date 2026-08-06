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
import com.simone.jarvismobile.data.ChatStore
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.data.StoredMessage
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.llm.LlmRouter
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.tools.CommandMatcher
import com.simone.jarvismobile.tools.ItalianNumbers
import com.simone.jarvismobile.tools.LlmIntentClassifier
import com.simone.jarvismobile.tools.Match
import com.simone.jarvismobile.tools.ToolOutcome
import com.simone.jarvismobile.tools.ToolRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    private val router: LlmRouter,
    private val chatStore: ChatStore,
) {

    /** Long-lived scope for fire-and-forget persistence; lives as long as the app. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val machine = ConversationStateMachine()
    val state: StateFlow<ConversationState> = machine.state

    val llmLoadState: StateFlow<LlmLoadState> = llm.loadState
    val loadedModelName: StateFlow<String?> = llm.loadedModelName

    private val systemPrompt: String by lazy {
        runCatching {
            context.assets.open("prompts/jarvis_system_it.md").bufferedReader().use { it.readText() }
        }.getOrDefault("Sei JARVIS, un assistente personale offline. Rispondi in italiano, breve e naturale.")
    }

    /**
     * The system instruction the live conversation was seeded with. It must stay
     * STABLE: changing it forces the engine to rebuild the conversation, which
     * throws away everything said so far. Rebuilt only by [newConversation].
     */
    @Volatile private var activeSystem: String? = null

    /** Note chunks already given to the model in this conversation. */
    private val injectedContext = LinkedHashSet<String>()

    /** True once the saved transcript has been read back from disk. */
    @Volatile private var chatRestored = false

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
        val trimmed = text.trim()
        // Remember whether JARVIS just asked something, so the next user message
        // is read as an answer rather than scanned for commands.
        if (!fromUser) awaitingAnswer = trimmed.endsWith("?")
        _messages.value = _messages.value + ChatMessage(fromUser, trimmed)
        persistChat()
    }

    /** Writes the transcript to disk so it survives the app being closed. */
    private fun persistChat() {
        val snapshot = _messages.value.map { StoredMessage(it.fromUser, it.text, System.currentTimeMillis()) }
        scope.launch { chatStore.save(snapshot) }
    }

    /**
     * Brings the previous conversation back after a restart: the transcript is
     * shown again, and (in [buildSystem]) recapped to the model, because the
     * engine's own KV cache dies with the process and cannot be restored.
     */
    suspend fun restoreChat() {
        if (chatRestored) return
        chatRestored = true
        val saved = runCatching { chatStore.load() }.getOrDefault(emptyList())
        if (saved.isEmpty() || _messages.value.isNotEmpty()) return
        _messages.value = saved.map { ChatMessage(it.fromUser, it.text) }
        _messages.value.lastOrNull()?.let { last ->
            if (!last.fromUser) awaitingAnswer = last.text.trim().endsWith("?")
        }
        Log.i(TAG, "chat_restored lines=${saved.size}")
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

    /**
     * True when JARVIS's own last reply ended with a question. The user's next
     * message is then an ANSWER in the conversation, so a stray keyword in it
     * ("agli appuntamenti") must not be hijacked into a command.
     */
    @Volatile private var awaitingAnswer = false

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
        // If JARVIS just asked something, this message answers it. Only an
        // unmistakable explicit command may interrupt; otherwise keep talking.
        if (awaitingAnswer) {
            val explicit = CommandMatcher.match(transcript)
            if (explicit is Match.Run) {
                _diagnostic.value = "tool: ${explicit.call.name}"
                return when (val outcome = tools.run(explicit.call)) {
                    is ToolOutcome.Done -> outcome.spoken
                    is ToolOutcome.Failed -> outcome.spoken
                    is ToolOutcome.NeedsConfirmation -> {
                        pendingConfirmation = outcome.call
                        outcome.prompt
                    }
                }
            }
            return chatReply(transcript)
        }

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
        // The fast model already judged whether this deserves the big brain.
        return chatReply(transcript, needsReasoning = intentClassifier.lastNeedsReasoning)
    }

    /**
     * Ordinary conversation with the model. The user's words go through VERBATIM;
     * context is never wrapped into a "Contesto … Domanda:" template, because that
     * invites a small model to CONTINUE the template instead of answering it.
     */
    private suspend fun chatReply(transcript: String, needsReasoning: Boolean = false): String {
        val retrieved = runCatching { memory.retrieve(transcript, MEMORY_TOP_K) }.getOrDefault(emptyList())
        val notes = retrieved.map { it.chunk.text.replace('\n', ' ').trim().take(600) }

        // The system instruction is built ONCE per conversation and then reused
        // verbatim. Rebuilding it every turn (it used to carry the retrieved
        // notes, which change with every question) forced the engine to recreate
        // the conversation, wiping the chat memory — the reason JARVIS could not
        // remember even the previous message.
        val system = activeSystem ?: buildSystem(notes).also {
            activeSystem = it
            injectedContext += notes
        }

        // Anything relevant that the model has not been shown yet rides along
        // with this turn instead of restarting the conversation.
        val fresh = notes.filterNot { it in injectedContext }
        injectedContext += fresh
        val message = if (fresh.isEmpty()) {
            transcript
        } else {
            "(dai miei appunti: " + fresh.joinToString(" · ") + ")\n" + transcript
        }

        val useBig = needsReasoning && router.hasAdvanced
        val brain = if (useBig) "avanzato" else "rapido"
        _diagnostic.value = if (retrieved.isEmpty()) {
            "penso ($brain)"
        } else {
            "memoria: ${retrieved.size} frammenti · $brain"
        }
        router.chat(message, system, needsReasoning)?.trim()?.ifBlank { null }?.let { return it }

        // Generation failed. The usual cause is an exhausted/corrupted KV cache
        // after a long chat, which a fresh conversation clears — so retry once
        // (losing the in-session history, not the vault memory) before giving up.
        Log.w(TAG, "llm_chat_null_retrying_fresh")
        _diagnostic.value = "riprovo con conversazione nuova"
        router.resetConversation()
        activeSystem = null
        injectedContext.clear()
        val retrySystem = buildSystem(notes).also { activeSystem = it; injectedContext += notes }
        router.chat(transcript, retrySystem, needsReasoning)?.trim()?.ifBlank { null }?.let { return it }

        return "Il modello non è riuscito a rispondere. Prova «Nuova conversazione», " +
            "o ricarica il modello dalla schermata Modelli."
    }

    /**
     * Seeds a conversation: who JARVIS is, what day it is, the notes known now,
     * and — crucially — a recap of what was being said before the app was closed.
     * The engine's multi-turn memory is a KV cache that dies with the process, so
     * this recap is the only way the conversation can continue an hour later.
     */
    private fun buildSystem(notes: List<String>): String = buildString {
        append(systemPrompt.trim())

        val now = LocalDateTime.now()
        append("\n\nAdesso è ")
        append(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN)))
        append(".")

        if (notes.isNotEmpty()) {
            append("\n\nAPPUNTI DI SIMONE (usali quando sono pertinenti, senza citarli se non serve):\n")
            notes.forEach { append("- ").append(it).append('\n') }
        }

        // The message being answered right now is sent as the user turn, so it
        // must not also appear in the recap or the model reads it as already dealt with.
        val history = _messages.value.dropLastWhile { it.fromUser }
        if (history.isNotEmpty()) {
            append("\n\nCONVERSAZIONE IN CORSO CON SIMONE (le battute precedenti, ")
            append("continuala senza ripeterla e senza salutare di nuovo):\n")
            history.takeLast(RECAP_MESSAGES).forEach { m ->
                append(if (m.fromUser) "Simone: " else "Tu: ")
                append(m.text.replace('\n', ' ').take(RECAP_CHARS))
                append('\n')
            }
        }
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
            // "Quando?" → parse the day out of the answer into a real date field.
            "add_reminder" -> {
                val note = pending.args["text"].orEmpty()
                (CommandMatcher.reminderFromAnswer(note, reply) as? Match.Run)?.call
            }

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
        restoreChat()
        val current = llm.loadState.value
        if (current == LlmLoadState.LOADED || current == LlmLoadState.LOADING) return
        val path = settings.modelPath.first()
        if (path.isBlank()) return
        val file = File(path)
        if (!file.exists()) return
        val name = settings.modelName.first().ifBlank { file.name }
        _diagnostic.value = "carico modello…"
        llm.load(path, name)
        loadAdvancedIfConfigured()
    }

    /**
     * Loads the optional larger model in the background. It is only used for
     * questions that need reasoning, so the assistant is usable long before this
     * finishes — and the app works exactly as before when none is configured.
     */
    private suspend fun loadAdvancedIfConfigured() {
        if (router.advancedLoadState.value == LlmLoadState.LOADED) return
        val path = settings.advancedModelPath.first()
        if (path.isBlank()) return
        val file = File(path)
        if (!file.exists()) return
        val name = settings.advancedModelName.first().ifBlank { file.name }
        _diagnostic.value = "carico modello avanzato…"
        router.advanced.load(path, name)
    }

    fun newConversation() {
        router.resetConversation()
        pendingConfirmation = null
        pendingSlot = null
        awaitingAnswer = false
        activeSystem = null
        injectedContext.clear()
        _messages.value = emptyList()
        _transcript.value = ""
        _reply.value = ""
        _diagnostic.value = "nuova conversazione"
        scope.launch { chatStore.clear() }
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

        /** How many past lines are recapped to the model when a chat resumes. */
        const val RECAP_MESSAGES = 16

        /** Cap per recapped line, so a long answer can't eat the context window. */
        const val RECAP_CHARS = 320

        /** Affirmative replies that approve a pending tool confirmation. */
        private val CONFIRM_WORDS = listOf("sì", "si", "certo", "conferma", "ok", "va bene", "procedi", "yes")

        /** Replies that cancel a pending tool confirmation. */
        private val DECLINE_WORDS = listOf("no", "annulla", "lascia", "niente", "ferma", "stop")
        private const val TAG = "JarvisSession"
    }
}
