package com.simone.jarvismobile.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.ai.AiRequest
import com.simone.jarvismobile.ai.AiRoutingContextProvider
import com.simone.jarvismobile.ai.RemoteAiEngine
import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.ai.AiRoutingHeuristic
import com.simone.jarvismobile.core.agenda.ItalianDateTimeParser
import com.simone.jarvismobile.core.state.ConversationEvent
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.core.state.ConversationStateMachine
import com.simone.jarvismobile.core.state.RouteTarget
import com.simone.jarvismobile.core.routing.AssistantReplyCleaner
import com.simone.jarvismobile.core.routing.ComplexityHeuristic
import com.simone.jarvismobile.core.routing.ToolIntentGate
import com.simone.jarvismobile.core.routing.TurnPlanner
import com.simone.jarvismobile.data.ChatStore
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.data.StoredMessage
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmGenerationTimeoutException
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.llm.LlmRouter
import com.simone.jarvismobile.llm.ModelSlot
import com.simone.jarvismobile.knowledge.KnowledgeRepository
import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.memory.ConversationMemoryStore
import com.simone.jarvismobile.automation.AutomationRepository
import com.simone.jarvismobile.automation.DeferredCommand
import com.simone.jarvismobile.core.automation.AutomationCodec
import com.simone.jarvismobile.core.automation.AutomationPhrase
import com.simone.jarvismobile.core.speech.Emotion
import com.simone.jarvismobile.core.speech.ExpressivePlanner
import com.simone.jarvismobile.core.speech.SpeechStyle
import com.simone.jarvismobile.core.translate.LiveTranslatorCommand
import com.simone.jarvismobile.core.translate.LiveTranslatorCommands
import com.simone.jarvismobile.translate.LiveTranslatorManager
import com.simone.jarvismobile.translate.LiveTranslatorRepository
import com.simone.jarvismobile.tools.CommandMatcher
import com.simone.jarvismobile.tools.ItalianNumbers
import com.simone.jarvismobile.tools.LlmIntentClassifier
import com.simone.jarvismobile.tools.Match
import com.simone.jarvismobile.tools.ToolOutcome
import com.simone.jarvismobile.util.runCancellable
import com.simone.jarvismobile.tools.ToolRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One line of the on-screen conversation log. */
data class ChatMessage(val fromUser: Boolean, val text: String, val taskId: String? = null)

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
    private val agendaIntents: com.simone.jarvismobile.tools.AgendaIntentRouter,
    private val router: LlmRouter,
    private val chatStore: ChatStore,
    private val knowledge: KnowledgeRepository,
    private val agenda: com.simone.jarvismobile.agenda.AgendaRepository,
    private val automations: AutomationRepository,
    private val conversationMemory: ConversationMemoryStore,
    private val liveTranslator: LiveTranslatorManager,
    private val liveTranslatorRepo: LiveTranslatorRepository,
    private val documents: com.simone.jarvismobile.document.DocumentImportManager,
    private val navigation: com.simone.jarvismobile.navigation.NavigationRepository,
    private val placeSearch: com.simone.jarvismobile.navigation.PlaceSearchRepository,
    private val drivingMode: com.simone.jarvismobile.driving.DrivingModeManager,
    private val proModeManager: com.simone.jarvismobile.promode.ProModeManager,
    private val proModeCoordinator: com.simone.jarvismobile.promode.ProModeCoordinator,
    private val engineRouter: com.simone.jarvismobile.engine.JarvisEngineRouter,
    private val conversationalEngine: com.simone.jarvismobile.engine.ConversationalJarvisEngine,
    private val remoteAiEngine: RemoteAiEngine,
    private val aiRoutingContextProvider: AiRoutingContextProvider,
    private val remoteChatState: com.simone.jarvismobile.ai.RemoteChatState,
) {

    /** Long-lived scope for fire-and-forget persistence; lives as long as the app. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val machine = ConversationStateMachine()
    val state: StateFlow<ConversationState> = machine.state

    init {
        // Auto-recover from a transient error/resting state back to Idle. Without
        // this the orb stayed red on ERRORE after, say, a wake-triggered session
        // that heard no further speech, and the foreground wake word (which only
        // runs while Idle) stopped responding until a manual press. A new session
        // — or any other transition — cancels the pending reset via collectLatest,
        // so this never interrupts a real conversation.
        scope.launch {
            machine.state.collectLatest { st ->
                if (!st.isAutoRecoverable()) return@collectLatest
                delay(ERROR_AUTO_RESET_MS)
                if (machine.state.value == st) {
                    _lastError.value = null
                    machine.dispatch(ConversationEvent.Reset) // -> Idle
                }
            }
        }
    }

    /**
     * Transient resting states the orb should leave on its own after a moment, so
     * hands-free listening resumes. States that need a user decision
     * (PermissionRequired, ModelUnavailable, VaultUnavailable) or a hard stop
     * (FatalError) are deliberately excluded — they wait for an explicit action.
     */
    private fun ConversationState.isAutoRecoverable(): Boolean = when (this) {
        is ConversationState.RecoverableError,
        ConversationState.BluetoothUnavailable,
        ConversationState.NetworkUnavailable,
        ConversationState.Cancelled -> true
        else -> false
    }

    val llmLoadState: StateFlow<LlmLoadState> = llm.loadState
    val loadedModelName: StateFlow<String?> = llm.loadedModelName

    /**
     * True while any brain has a native call in flight, including the window
     * right after a cancel was requested but the call hasn't unwound yet — see
     * [LlmRouter.generating]. Lets the dashboard show "still finishing up"
     * distinctly from a conversation state that already reset to "Pronto".
     */
    val llmGenerating: Flow<Boolean> = router.generating

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
    private val activeSystems = mutableMapOf<ModelSlot, String>()
    private val activeEpochs = mutableMapOf<ModelSlot, Long>()

    /** Note chunks already given to each model in this conversation. */
    private val injectedContexts = mutableMapOf<ModelSlot, LinkedHashSet<String>>()

    /**
     * The two local models have separate KV caches. When routing changes, the
     * destination brain is reseeded from the shared transcript so "rapido" and
     * "avanzato" still behave like one assistant with one short-term memory.
     */
    @Volatile private var lastConversationSlot: ModelSlot? = null

    /**
     * The assistant's configurable name. It was never given to the model, which
     * is why "come ti chiami?" answered "sono un assistente personale di Simone"
     * instead of "JARVIS". Cached because the system prompt is built off the
     * main thread without suspending.
     */
    @Volatile private var assistantName: String = SettingsRepository.DEFAULT_NAME

    /**
     * The user's configurable personality instruction, prepended to the base
     * system prompt in [buildSystem]. Cached like [assistantName] because the
     * prompt is built off the main thread without suspending; refreshed when the
     * chat is (re)opened and on "Nuova conversazione".
     */
    @Volatile private var persona: String = SettingsRepository.DEFAULT_PERSONA

    /** Whether to offer to remember durable facts heard in conversation. */
    @Volatile private var autoMemoryCapture: Boolean = true

    /** Facts already offered this conversation, so JARVIS never re-asks the same one. */
    private val offeredFacts = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The last slot question asked, so it is never repeated verbatim. */
    @Volatile private var lastAskedSlot: String? = null

    /** An agenda entry JARVIS offered to tick off, awaiting a yes/no. */
    @Volatile private var pendingCompletion: com.simone.jarvismobile.core.agenda.AgendaEntry? = null

    /** An agenda entry just created, for which an alert was offered. */
    @Volatile private var pendingAlertFor: String? = null

    /** True once the saved transcript has been read back from disk. */
    @Volatile private var chatRestored = false

    val micLevel: StateFlow<Float> = audioCapture.micLevel
    val routeState: StateFlow<AudioRouteState> = audioRouteManager.routeState
    val ttsState: StateFlow<TtsState> = tts.state
    val selectedVoiceName: StateFlow<String?> = tts.selectedVoiceName
    val availableVoices: StateFlow<List<TtsVoiceOption>> = tts.availableVoices
    val partialTranscript: StateFlow<String> = stt.partial

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _reply = MutableStateFlow("")
    val reply: StateFlow<String> = _reply.asStateFlow()

    /** On-screen conversation log (mirrors the model's in-session memory). */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    val shortTermMemory = conversationMemory.snapshot

    private fun appendMessage(fromUser: Boolean, text: String, taskId: String? = null) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        if (taskId != null && _messages.value.any { it.fromUser == fromUser && it.taskId == taskId }) return
        // Remember whether JARVIS just asked something, so the next user message
        // is read as an answer rather than scanned for commands.
        if (!fromUser) {
            awaitingAnswer = trimmed.endsWith("?")
            if (trimmed.endsWith(ALERT_OFFER)) {
                // The entry just filed is the newest one still without alerts.
                pendingAlertFor = agenda.entries.value
                    .lastOrNull { it.alerts.isEmpty() && !it.done }?.id
            }
        }
        _messages.value = _messages.value + ChatMessage(fromUser, trimmed, taskId)
        persistChat()
    }

    /** Writes the transcript to disk so it survives the app being closed. */
    private fun persistChat() {
        val snapshot = _messages.value.map {
            StoredMessage(it.fromUser, it.text, System.currentTimeMillis(), it.taskId)
        }
        scope.launch {
            chatStore.save(snapshot)
            conversationMemory.update(snapshot)
        }
    }

    /**
     * Brings the previous conversation back after a restart: the transcript is
     * shown again, and (in [buildSystem]) recapped to the model, because the
     * engine's own KV cache dies with the process and cannot be restored.
     */
    suspend fun restoreChat() {
        assistantName = runCatching { settings.assistantName.first() }
            .getOrDefault(SettingsRepository.DEFAULT_NAME)
            .ifBlank { SettingsRepository.DEFAULT_NAME }
        persona = runCatching { settings.personaPrompt.first() }.getOrDefault(SettingsRepository.DEFAULT_PERSONA)
        autoMemoryCapture = runCatching { settings.autoMemoryCapture.first() }.getOrDefault(true)
        if (chatRestored) return
        chatRestored = true
        conversationMemory.ensureLoaded()
        val saved = runCatching { chatStore.load() }.getOrDefault(emptyList())
        if (saved.isEmpty() || _messages.value.isNotEmpty()) return
        _messages.value = saved.map { ChatMessage(it.fromUser, it.text, it.taskId) }
        _messages.value.lastOrNull()?.let { last ->
            if (!last.fromUser) awaitingAnswer = last.text.trim().endsWith("?")
        }
        Log.i(TAG, "chat_restored lines=${saved.size}")
    }

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _diagnostic = MutableStateFlow("")
    val diagnostic: StateFlow<String> = _diagnostic.asStateFlow()

    /**
     * "Chi ha risposto davvero all'ultimo messaggio?" — vedi
     * [com.simone.jarvismobile.ai.RemoteChatState]'s own doc comment. Written
     * by [tryRemoteChat] (Motore Classico) AND by `JarvisBrain.tryRemoteReply`
     * (Motore Conversazionale) into the SAME shared state, so this one
     * pass-through reflects whichever engine actually ran the last turn —
     * never a stale reading from the other engine.
     */
    val lastChatRoute: StateFlow<String?> = remoteChatState.lastRoute

    /** True while a typed (written-chat) message is being answered. */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val sessionMutex = Mutex()

    /**
     * The coroutine running the current voice turn, owned by the coordinator's own
     * [scope] (not the caller's) so [cancel] can actually stop it. Launching from a
     * viewModelScope meant a stop only cancelled the recognizer while this coroutine
     * stayed suspended inside stt.transcribe() holding [sessionMutex] — so the next
     * press hit "already_running" and the orb froze on "Pronto".
     */
    @Volatile private var sessionJob: Job? = null

    /** A tool call awaiting the user's spoken/typed confirmation, if any. */
    @Volatile private var pendingConfirmation: com.simone.jarvismobile.core.protocol.ToolCall? = null

    /** A tool JARVIS asked a follow-up question about, with what it already knows. */
    private data class Pending(val tool: String, val missing: String, val args: Map<String, String>)

    @Volatile private var pendingSlot: Pending? = null

    /**
     * Set when several planner entries matched a delete/move/rename and JARVIS
     * asked which one. Kept apart from [pendingSlot] because the answer is a
     * *choice among known candidates*, not a missing argument — and because a
     * destructive action must not proceed until that choice is made.
     */
    private data class PendingPick(val candidateIds: List<String>, val args: Map<String, String>)

    @Volatile private var pendingPick: PendingPick? = null

    /**
     * Set when offline destination search found several plausible candidates and
     * none dominated (spec §9, §10 — [com.simone.jarvismobile.core.navigation.ResolvedDestination.Ambiguous]).
     * Kept apart from [pendingPick] for the same reason that one is kept apart
     * from [pendingSlot]: the answer picks among known candidates rather than
     * filling a missing argument, and it is its own destination-only concern —
     * reusing the exact shape already proven for agenda disambiguation rather
     * than inventing a second dialog manager.
     */
    private data class PendingDestinationPick(
        val candidates: List<com.simone.jarvismobile.core.navigation.PlaceHit>,
        val options: com.simone.jarvismobile.core.navigation.RouteOptions,
    )

    @Volatile private var pendingDestinationPick: PendingDestinationPick? = null

    /**
     * The planner entry the conversation is currently about, so "spostalo alle
     * 16" has something to bind to. Set whenever an entry is acted upon.
     */
    @Volatile private var lastAgendaEntryId: String? = null

    /**
     * True when JARVIS's own last reply ended with a question. The user's next
     * message is then an ANSWER in the conversation, so a stray keyword in it
     * ("agli appuntamenti") must not be hijacked into a command.
     */
    @Volatile private var awaitingAnswer = false

    /** Set by a visible mic press while TTS is speaking (barge-in). */
    @Volatile private var bargeInRequested = false

    /** True after JARVIS asked which two languages to translate between. */
    @Volatile private var awaitingTranslatorLanguages = false

    /**
     * Set when this turn just handed the microphone to the Live Translator. The
     * voice session must then NOT re-open its follow-up mic: two recognizers on
     * the one shared engine would fight over the microphone.
     */
    @Volatile private var translatorTakingOver = false

    /**
     * The ONLY entry point for starting a voice turn — orb, tile, wake word and
     * Modalità Guida all call this, never [runSession] directly, so that every
     * running turn is always reachable through [sessionJob] and therefore always
     * cancellable by [cancel]. A turn started by calling [runSession] directly
     * would run untracked: [cancel] would stop the recognizer but have no job to
     * cancel, leaving the orb stuck until the STT engine's own timeout.
     *
     * Launches the turn on the coordinator's own scope and remembers the job,
     * first waiting (up to [SESSION_HANDOFF_TIMEOUT_MS]) for any previous turn
     * to fully unwind so its [sessionMutex] is released — this is what lets a
     * stopped session restart instead of freezing on "Pronto". The wait is
     * bounded, not `cancelAndJoin()`'s unbounded one: a blocking native LLM
     * call cannot be interrupted by cancelling this coroutine alone (see
     * [SESSION_HANDOFF_TIMEOUT_MS]'s doc), so an unbounded wait here meant the
     * new session could never actually start until that orphaned call
     * finished on its own. If the bound is hit, the new session is attempted
     * anyway; [runSession] handles the (rarer, now bounded) case where the
     * mutex genuinely still isn't free.
     */
    fun startSession() {
        val previous = sessionJob
        sessionJob = scope.launch {
            if (previous != null) {
                previous.cancel()
                withTimeoutOrNull(SESSION_HANDOFF_TIMEOUT_MS) { previous.join() }
            }
            runSession()
        }
    }

    /** Runs one conversation turn. Safe to call repeatedly; ignores overlap. Only reachable via [startSession] — see its doc. */
    private suspend fun runSession() {
        // A visible talk press always wins over optional background speech.
        if (tts.state.value == TtsState.SPEAKING && state.value != ConversationState.Speaking) {
            tts.stop()
        }
        if (sessionMutex.isLocked) {
            // The previous turn's blocking native call still hasn't returned even
            // after startSession()'s bounded wait — say so plainly instead of
            // silently doing nothing, which used to look identical to a genuine
            // freeze (the orb already reads "Pronto" from CancelRequested, so a
            // silent no-op here gave no signal anything was still wrong).
            Log.i(TAG, "session_skip already_running")
            _diagnostic.value = "sto ancora concludendo la richiesta precedente…"
            _lastError.value = "still_finishing_previous"
            machine.dispatch(ConversationEvent.RecoverableFailure("still_finishing_previous"))
            return
        }
        sessionMutex.withLock {
            _lastError.value = null
            _transcript.value = ""
            _reply.value = ""
            _diagnostic.value = "start"
            try {
                runTurn()
            } catch (_: CancellationException) {
                _diagnostic.value = "sessione annullata"
                machine.dispatch(ConversationEvent.CancelRequested)
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

            // The user pressed the mic while JARVIS was talking. TTS has been
            // stopped; skip the normal follow-up preference and listen now.
            if (bargeInRequested) {
                bargeInRequested = false
                _diagnostic.value = "interrotto: ti ascolto…"
                _transcript.value = ""
                machine.dispatch(ConversationEvent.SpeechStarted)
                machine.dispatch(ConversationEvent.AudioReady)
                turn++
                continue
            }

            // The Live Translator just took the microphone: end this session
            // quietly instead of re-opening a follow-up that would fight it.
            if (translatorTakingOver) {
                translatorTakingOver = false
                machine.dispatch(ConversationEvent.FollowUpTimeout) // -> Idle
                return
            }

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
            is SttResult.Text -> if (
                pendingConfirmation == null && pendingSlot == null && pendingPick == null &&
                pendingDestinationPick == null && !awaitingAnswer &&
                com.simone.jarvismobile.core.intent.IntentAliases.isListeningCancelWord(result.text)
            ) {
                // A stray/automatic "Ok" — wake word or an accidental press — with
                // nothing actually pending means "stop listening", not a request:
                // no LLM call, no chat-log entry, no spoken reply. Same quiet
                // return-to-Idle as SttResult.NoSpeech below.
                _diagnostic.value = "ascolto interrotto (\"ok\")"
                machine.dispatch(ConversationEvent.Reset) // -> Idle
                false
            } else {
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
     * The single switch between Classic and Conversational AI (Motore JARVIS —
     * see `docs/CONVERSATIONAL_ENGINE.md`), the exact one-`if` gate pattern
     * `engineRouter` itself documents. [classicAnswer] below is Classic mode's
     * entire existing behaviour, untouched; `conversationalEngine` is the new
     * LLM-first orchestrator. Neither knows about the other directly — the
     * router is the only place that chooses.
     */
    private suspend fun generateAnswer(transcript: String): String =
        engineRouter.route(
            transcript,
            classic = com.simone.jarvismobile.engine.ClassicJarvisEngine { classicAnswer(it) },
            conversational = conversationalEngine,
        )

    /**
     * Classic mode's entire turn-dispatch behaviour (deterministic command
     * matching, Modalità Pro, the translator/navigation/automation phrase
     * parsers, `TurnPlanner`, chat fallback) — moved here unedited from what
     * used to be `generateAnswer`'s own body when the Conversational AI engine
     * switch was introduced. When a local model is loaded it answers for real
     * via a multi-turn [LlmEngine.chat] that REMEMBERS the earlier exchanges
     * (the model keeps the conversation history); otherwise it falls back to
     * the echo and points the user to the Models screen.
     */
    private suspend fun classicAnswer(transcript: String): String {
        // Modalità Pro (spec §1/§2): the on/off phrase is checked before ANY
        // other routing decision, in both NORMAL and PRO, so "esci dalla
        // modalità pro" always works — including with a Pro-mode tool
        // confirmation pending, since that lives in proModeCoordinator, not
        // here, and this check runs before proModeCoordinator is ever asked.
        proModeManager.interceptSystemCommand(transcript)?.let { spoken ->
            if (com.simone.jarvismobile.core.promode.ProModeCommands.parse(transcript) ==
                com.simone.jarvismobile.core.promode.ProModeCommand.DEACTIVATE
            ) {
                proModeCoordinator.reset()
            }
            return spoken
        }
        // PRO bypasses every deterministic/alias path below (CommandMatcher,
        // TurnPlanner, LlmIntentClassifier, the translator/navigation/automation
        // phrase parsers, offerFactCapture): the local model is the sole router,
        // reached through its own tool-call loop. This is the ONLY place PRO
        // mode is checked in the whole turn pipeline — see ProModeCoordinator's
        // class doc for why the behavior itself is not scattered here too.
        if (proModeManager.currentlyActive()) {
            return proModeCoordinator.handle(transcript)
        }

        // If JARVIS asked "Tra quali lingue?" the previous turn, this message is
        // the answer — handle it before anything else, since the question mark set
        // the generic awaitingAnswer flag that would otherwise route it to chat.
        if (awaitingTranslatorLanguages) {
            handleTranslatorCommand(transcript)?.let { return it }
        }

        // An offer JARVIS made on the previous turn takes precedence: the user is
        // answering it, not starting something new.
        answerPendingOffer(transcript)?.let { return it }

        // Live Translator voice control: "avvia traduzione live italiano
        // giapponese", "ferma la traduzione", "scambia le lingue". Handled before
        // the command/LLM path so a translator phrase never becomes a chat turn.
        // pendingDestinationPick is checked here too: JARVIS's own disambiguation
        // question ("via Roma a Frascati o a Roma?") is answered in
        // generateAtomicAnswer below, but a reply that itself happens to parse as
        // a fresh NavIntent (e.g. containing "portami a") would otherwise be
        // hijacked into starting an unrelated navigation right here, before that
        // answer is ever reached.
        if (pendingConfirmation == null && pendingSlot == null && pendingDestinationPick == null && !awaitingAnswer) {
            handleTranslatorCommand(transcript)?.let { return it }
            // Offline navigation voice control: "portami a…", "distributore più
            // vicino", "quanto manca?", "ferma navigazione". Coordinates come only
            // from the offline place index or a favourite — never from the model.
            handleNavigationCommand(transcript)?.let { return it }
        }

        // "Sono andato dal dentista" is a statement, but if the dentist is an open
        // item the useful move is to offer to tick it off rather than leave a
        // reminder to fire for something that already happened. Only an offer —
        // misreading a sentence must never silently rewrite the calendar.
        if (pendingConfirmation == null && pendingSlot == null && !awaitingAnswer) {
            val open = runCatching { agenda.entries.value }.getOrDefault(emptyList())
            com.simone.jarvismobile.core.agenda.CompletionHint.detect(transcript, open)?.let { hit ->
                pendingCompletion = hit
                return "Hai completato “${hit.text}”? Se vuoi lo segno come fatto."
            }
        }

        // A standing rule, dictated. "ogni giorno alle 8 ricordami di…" is an
        // instruction that outlives the turn, while "domani alle 15 il dentista"
        // is an appointment — the parser refuses anything not explicitly
        // recurring or conditional, so the second still falls through to the
        // agenda below. What was created is read straight back, because a rule
        // the user cannot see is a rule they cannot undo.
        if (pendingConfirmation == null && pendingSlot == null && !awaitingAnswer) {
            AutomationPhrase.parse(transcript)?.let { rule ->
                val saved = runCatching { automations.add(rule) }.getOrDefault(false)
                return if (saved) {
                    "Automazione creata: ${AutomationCodec.describe(rule)}."
                } else {
                    // Name the reason. "Non sono riuscito" on its own is a dead
                    // end for the user and for whoever has to fix it.
                    val why = automations.lastError.value
                    "Non sono riuscito a salvare l'automazione" +
                        (if (why.isBlank()) "." else " ($why).")
                }
            }

            // A device command aimed at a future minute — "alle 11.45 accendi la
            // torcia" — is a one-shot rule, not something to run right now.
            // Caught before the command path below, which would otherwise fire
            // the torch this instant. An appointment still falls through: its
            // remainder maps to no tool, so DeferredCommand returns null.
            DeferredCommand.parse(transcript)?.let { rule ->
                val saved = runCatching { automations.add(rule) }.getOrDefault(false)
                return if (saved) {
                    "Automazione creata: ${AutomationCodec.describe(rule)}."
                } else {
                    val why = automations.lastError.value
                    "Non sono riuscito a salvare l'automazione" +
                        (if (why.isBlank()) "." else " ($why).")
                }
            }

            // A plain sentence that states a durable fact about the user ("mi
            // chiamo…", "sono allergico a…") is worth remembering. JARVIS only
            // OFFERS — the save still goes through the confirming-write path — so a
            // misread can never silently write the vault.
            offerFactCapture(transcript)?.let { return it }
        }

        // Pending yes/no or slot answers must remain a single conversational
        // turn. Otherwise route every explicitly punctuated question on its own:
        // a 1B model commonly answers only the first part of a multi-question
        // request, and the old command path stopped after the first tool match.
        if (pendingConfirmation == null && pendingSlot == null && !awaitingAnswer) {
            val plan = TurnPlanner.plan(transcript)
            if (plan.isCompound) {
                // Knowledge/conversation questions belong to ONE model turn.
                // Splitting them made a 1B model classify+answer every clause and
                // fed its own partial replies back as pseudo-dialogue, causing
                // severe latency and role continuations such as "Tu:".
                val recentContext = recentConversationContext()
                val deterministic = plan.requests.map { request ->
                    CommandMatcher.match(request.text, recentContext = recentContext)
                }
                val needsClassifier = plan.requests.indices.any { index ->
                    deterministic[index] == null && ToolIntentGate.shouldClassify(plan.requests[index].text)
                }
                if (deterministic.all { it == null } && !needsClassifier) {
                    return chatReply(
                        transcript,
                        needsReasoning = ComplexityHeuristic.needsReasoning(transcript),
                    )
                }

                // Read-only/deterministic results can be grounded into one final
                // conversational answer. This keeps mixed requests fast, e.g.
                // three tagliando questions plus "ne ho uno in programma?".
                val canComposeOnce = !needsClassifier && deterministic.filterNotNull().all { match ->
                    match is Match.Run && match.call.name in SAFE_COMPOUND_TOOLS
                }
                if (canComposeOnce) {
                    val verified = ArrayList<Pair<String, String>>()
                    plan.requests.forEachIndexed { index, request ->
                        val match = deterministic[index] as? Match.Run ?: return@forEachIndexed
                        val spoken = when (val outcome = tools.run(match.call)) {
                            is ToolOutcome.Done -> outcome.spoken
                            is ToolOutcome.Failed -> outcome.spoken
                            is ToolOutcome.NeedsConfirmation -> {
                                pendingConfirmation = outcome.call
                                return outcome.prompt
                            }
                        }
                        verified += request.text to spoken
                    }
                    if (verified.size == plan.requests.size) {
                        return verified.joinToString("\n") { it.second }
                    }
                    if (verified.isNotEmpty()) {
                        val grounding = verified.joinToString("\n") { (request, result) ->
                            "$request -> $result"
                        }
                        return chatReply(
                            transcript,
                            needsReasoning = true,
                            conversationHint = grounding,
                        )
                    }
                }

                val answers = ArrayList<String>(plan.requests.size)
                var sameMessageContext = ""
                for (request in plan.requests) {
                    val answer = generateAtomicAnswer(
                        transcript = request.text,
                        sameMessageContext = sameMessageContext,
                        fallbackNeedsReasoning = request.needsReasoningFallback,
                    )
                    if (answer.isNotBlank() && answers.none { it.equals(answer, ignoreCase = true) }) {
                        answers += answer
                    }
                    sameMessageContext = buildString {
                        if (sameMessageContext.isNotBlank()) append(sameMessageContext).append('\n')
                        append("Simone: ").append(request.text).append('\n')
                        append("JARVIS: ").append(answer)
                    }.takeLast(COMPOUND_CONTEXT_CHARS)
                }
                if (answers.isNotEmpty()) return answers.joinToString("\n")
            }
        }
        return generateAtomicAnswer(transcript)
    }

    /** Routes and answers one indivisible request. */
    private suspend fun generateAtomicAnswer(
        transcript: String,
        sameMessageContext: String = "",
        fallbackNeedsReasoning: Boolean = false,
    ): String {
        val recentContext = recentConversationContext(sameMessageContext)
        // A pending confirmation is answered by this utterance (yes / no).
        pendingConfirmation?.let { call ->
            val aliases = com.simone.jarvismobile.core.intent.IntentAliases
            // "cancella" is checked first and only on its own: while a
            // confirmation is open it means "call this off", not "delete
            // something". With words after it ("cancella la nota sulla moto") it
            // is a new request, so it falls through to the normal path below.
            if (aliases.isCancellationOfPendingAction(transcript)) {
                pendingConfirmation = null
                return "Va bene, annullato."
            }
            if (aliases.isAffirmative(transcript)) {
                pendingConfirmation = null
                _diagnostic.value = "tool confermato: ${call.name}"
                return when (val outcome = tools.run(call, confirmed = true)) {
                    is ToolOutcome.Done -> outcome.spoken
                    is ToolOutcome.Failed -> outcome.spoken
                    is ToolOutcome.NeedsConfirmation -> "Non posso eseguirlo senza conferma."
                }
            }
            if (aliases.isNegative(transcript)) {
                pendingConfirmation = null
                return "Va bene, non lo faccio."
            }
            // Neither yes nor no: drop the pending action and treat this as a
            // brand-new request rather than silently executing something.
            pendingConfirmation = null
        }

        // JARVIS asked WHICH of several planner entries was meant. That question
        // is answered before anything else is considered: until it is, there is a
        // half-finished action on the table, possibly a deletion.
        pendingPick?.let { pick ->
            if (com.simone.jarvismobile.core.intent.IntentAliases.isCancellationOfPendingAction(transcript) ||
                com.simone.jarvismobile.core.intent.IntentAliases.isNegative(transcript)
            ) {
                pendingPick = null
                return "Va bene, lascio stare."
            }
            pendingPick = null
            when (
                val resolved = agendaIntents.resolvePick(pick.args, pick.candidateIds, transcript)
            ) {
                is com.simone.jarvismobile.tools.AgendaRouting.Call ->
                    return runAgendaCall(resolved.call)
                is com.simone.jarvismobile.tools.AgendaRouting.Disambiguate -> {
                    pendingPick = PendingPick(resolved.candidateIds, resolved.pending)
                    return resolved.question
                }
                is com.simone.jarvismobile.tools.AgendaRouting.NotFound -> return resolved.spoken
            }
        }

        // JARVIS asked which of several offline-search destination candidates was
        // meant ("Via Roma a Frascati o a Roma?"). Answered before anything else
        // for the same reason as pendingPick above.
        pendingDestinationPick?.let { pick ->
            pendingDestinationPick = null
            if (com.simone.jarvismobile.core.intent.IntentAliases.isCancellationOfPendingAction(transcript) ||
                com.simone.jarvismobile.core.intent.IntentAliases.isNegative(transcript)
            ) {
                return "Va bene, lascio stare."
            }
            val chosen = pickDestination(pick.candidates, transcript)
            if (chosen == null) {
                pendingDestinationPick = pick
                return "Non ho capito quale dei due intendi. Prova a dire la città, oppure \"il primo\"/\"il secondo\"."
            }
            navigation.startNavigation(chosen.location, pick.options)
            if (!drivingMode.state.value.active) navigation.requestOpenScreen()
            scope.launch { runCatching { placeSearch.addHistory(chosen.name, chosen.location) } }
            return "Avvio la navigazione verso ${chosen.name}."
        }

        // The user is answering a question JARVIS asked ("Per quanto tempo?").
        pendingSlot?.let { pending ->
            val toolName = pending.tool
            pendingSlot = null
            when (val completed = fillSlot(pending, transcript)) {
                is Match.Ask -> {
                    pendingSlot = Pending(completed.tool, completed.missing, completed.partial)
                    _diagnostic.value = "chiedo: ${completed.missing}"
                    return completed.question
                }
                is Match.Run -> {
                    _diagnostic.value = "tool completato: $toolName"
                    return when (val outcome = tools.run(completed.call)) {
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
            // Couldn't read an answer out of it — fall through and treat the
            // utterance as a fresh request rather than guessing.
        }

        // Phase 6 — deterministic operations first. This path is instant and
        // avoids wasting a full model generation on explicit commands.
        // If JARVIS just asked something, this message answers it. Only an
        // unmistakable explicit command may interrupt; otherwise keep talking.
        if (awaitingAnswer) {
            val explicit = CommandMatcher.match(transcript, recentContext = recentContext)
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
            return chatReply(
                transcript,
                needsReasoning = lastConversationSlot == ModelSlot.ADVANCED,
                conversationHint = sameMessageContext,
            )
        }

        when (val match = CommandMatcher.match(transcript, recentContext = recentContext)) {
            is Match.Ask -> {
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
            null -> Unit
        }

        // Structured path: planner CRUD and reschedules ("sposta il dentista a
        // venerdì", "cancella la riunione delle 18", "spostalo di due ore").
        // Deterministic and instant — the model is not involved, and the target is
        // resolved to a real entry id before any tool can touch the calendar.
        runCancellable { agendaIntents.route(transcript, contextEntryId = lastAgendaEntryId) }
            .getOrNull()
            ?.let { routing ->
                when (routing) {
                    is com.simone.jarvismobile.tools.AgendaRouting.Call ->
                        return runAgendaCall(routing.call)
                    is com.simone.jarvismobile.tools.AgendaRouting.Disambiguate -> {
                        pendingPick = PendingPick(routing.candidateIds, routing.pending)
                        _diagnostic.value = "disambiguo: ${routing.candidateIds.size} candidati"
                        return routing.question
                    }
                    is com.simone.jarvismobile.tools.AgendaRouting.NotFound -> return routing.spoken
                }
            }

        // Only plausible but unfamiliar operation wording pays for the optional
        // one-line LLM classifier. Ordinary chat goes directly to one answer.
        val understanding = if (ToolIntentGate.shouldClassify(transcript)) {
            try {
                intentClassifier.understand(transcript, recentContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: LlmGenerationTimeoutException) {
                null
            }
        } else {
            null
        }
        when (val aiMatch = understanding?.takeIf { it.mayExecute }?.match) {
            is Match.Ask -> {
                val key = aiMatch.tool + "/" + aiMatch.missing
                if (lastAskedSlot == key) {
                    // Already asked this and the reply did not fit. Asking again
                    // is how the assistant ends up repeating one question for
                    // ever; answer conversationally instead.
                    lastAskedSlot = null
                    pendingSlot = null
                    return chatReply(transcript, needsReasoning = intentClassifier.lastNeedsReasoning)
                }
                lastAskedSlot = key
                pendingSlot = Pending(aiMatch.tool, aiMatch.missing, aiMatch.partial)
                _diagnostic.value = "chiedo (piano): ${aiMatch.missing}"
                return aiMatch.question
            }
            is Match.Run -> {
                _diagnostic.value = "tool (piano): ${aiMatch.call.name}"
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

        if (llm.loadState.value != LlmLoadState.LOADED) {
            return "Ho capito: $transcript. Carica un modello nella schermata Modelli per risposte vere."
        }
        // The fast model already judged whether this deserves the big brain.
        return chatReply(
            transcript,
            needsReasoning = fallbackNeedsReasoning || understanding?.needsReasoning == true,
            conversationHint = sameMessageContext,
        )
    }

    /**
     * Runs a planner call produced by the structured path and remembers which
     * entry it was about, so a following "spostalo alle 17" resolves without the
     * user naming it again. A delete clears that memory instead of keeping a
     * pointer to something that no longer exists.
     */
    private suspend fun runAgendaCall(call: com.simone.jarvismobile.core.protocol.ToolCall): String {
        val entryId = runCatching {
            call.arguments["id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()
        _diagnostic.value = "tool (intent): ${call.name}"
        return when (val outcome = tools.run(call)) {
            is ToolOutcome.Done -> {
                lastAgendaEntryId = if (call.name == "delete_agenda") null else entryId
                outcome.spoken
            }
            is ToolOutcome.Failed -> outcome.spoken
            is ToolOutcome.NeedsConfirmation -> {
                pendingConfirmation = outcome.call
                lastAgendaEntryId = entryId
                outcome.prompt
            }
        }
    }

    /** Previous turns for resolving pronouns without exposing the whole chat to the classifier. */
    private fun recentConversationContext(extra: String = ""): String {
        val messages = _messages.value
            .let { all -> if (all.lastOrNull()?.fromUser == true) all.dropLast(1) else all }
            // JARVIS's own questions must never enter the classifier's context.
            // Once it asked "Quale numero devo preparare nel dialer?", that line
            // stayed in context and the classifier kept reading "dialer" and
            // re-picking prepare_call for every following message — including
            // "Ciao". The assistant was answering its own prompt in a loop.
            .filterNot { !it.fromUser && it.text.trimEnd().endsWith("?") }
        return buildString {
            messages.takeLast(CONTEXT_MESSAGES).forEach { message ->
                append(if (message.fromUser) "Simone: " else "JARVIS: ")
                append(message.text.replace('\n', ' ').take(CONTEXT_LINE_CHARS))
                append('\n')
            }
            if (extra.isNotBlank()) append(extra)
        }.takeLast(CLASSIFIER_CONTEXT_CHARS)
    }

    /**
     * Tries JARVIS Core for this turn before the local model below — the
     * same [AiRoutingHeuristic]/[AiRoutingContextProvider] JARVIS Core's own
     * AI Router already uses, called directly rather than through
     * `AiRouter.generate()` itself: that method's own local fallback goes
     * through [com.simone.jarvismobile.ai.LocalAiEngine]'s single-shot
     * `LlmRouter.chat(...)`, which would skip [chatReply]'s system-prompt
     * caching, injected-context dedup and KV-cache-corruption retry below —
     * real behaviour this integration must not touch (§ "NON creare una
     * seconda pipeline chat"). So this only ever *replaces* the outcome when
     * remote succeeds; on any failure it returns `null` and [chatReply]
     * falls through to its existing, completely unmodified local block.
     * With `coreEnabled`/`remoteAiEnabled` off (the default) this always
     * returns `null` immediately — the local path is then byte-for-byte
     * what it was before this integration.
     */
    private suspend fun tryRemoteChat(message: String, system: String, needsReasoning: Boolean): String? {
        val requestType = if (needsReasoning) AiRequestType.COMPLEX else AiRequestType.CHAT
        val decision = AiRoutingHeuristic.decide(requestType, aiRoutingContextProvider.preferencesFor(requestType))
        if (decision.target == AiExecutionTarget.LOCAL) {
            // Distinguishes, at a glance in Logcat, every reason an "ordinary
            // chat" turn never left the device: toggles genuinely off
            // (remote_ai_disabled/caller_forbids_remote) vs. Core reachable
            // by "Testa connessione" (which probes independently of
            // `coreEnabled`) but the actual routing gate — `coreEnabled`
            // itself — still off (core_disabled), or Core reachable but
            // offline/degraded-without-brain (core_offline/core_error). Never
            // logs message content, only the routing reason (§ "non loggare
            // dati personali").
            Log.i(TAG, "CHAT -> LOCAL (reason=${decision.reason})")
            remoteChatState.setLastRoute("LOCAL (${decision.reason})")
            return null
        }

        val targetLabel = if (decision.target == AiExecutionTarget.REMOTE_BRAIN) "CORE BRAIN" else "CORE FAST"
        _diagnostic.value = "JARVIS Core…"
        val requestId = java.util.UUID.randomUUID().toString()
        val request = AiRequest(
            requestId = requestId,
            text = message,
            systemPrompt = system,
            requestType = requestType,
            preferredModel = if (decision.target == AiExecutionTarget.REMOTE_BRAIN) "brain" else null,
        )
        remoteChatState.activeRequestId = requestId
        val result = try {
            runCancellable { remoteAiEngine.generate(request) }.getOrNull()
        } finally {
            // Must clear even when a CancellationException propagates through
            // runCancellable — otherwise a cancelled remote call would leave
            // the shared active-request id dangling and a later
            // cancelTextGeneration() would try to cancel a request that
            // already unwound.
            remoteChatState.activeRequestId = null
        }
        if (result == null || !result.success) {
            val reason = result?.failureReason ?: "cancelled_or_null"
            Log.i(TAG, "CORE FAILED -> LOCAL FALLBACK (reason=$reason)")
            remoteChatState.setLastRoute("LOCAL (fallback dopo Core: $reason)")
            return null
        }
        val cleaned = result.text?.let(AssistantReplyCleaner::clean)?.ifBlank { null }
        if (cleaned == null) {
            Log.i(TAG, "CORE FAILED -> LOCAL FALLBACK (reason=empty_reply)")
            remoteChatState.setLastRoute("LOCAL (fallback dopo Core: empty_reply)")
            return null
        }
        Log.i(TAG, "CHAT -> $targetLabel")
        remoteChatState.setLastRoute(targetLabel)
        return cleaned
    }

    /**
     * Ordinary conversation with the model. The user's words go through VERBATIM;
     * context is never wrapped into a "Contesto … Domanda:" template, because that
     * invites a small model to CONTINUE the template instead of answering it.
     */
    private suspend fun chatReply(
        transcript: String,
        needsReasoning: Boolean = false,
        conversationHint: String = "",
    ): String {
        lastAskedSlot = null
        val retrieved = runCancellable { memory.retrieveSmart(transcript, MEMORY_TOP_K) }.getOrDefault(emptyList())
        val notes = retrieved.map { it.chunk.text.replace('\n', ' ').trim().take(600) }

        val slot = router.selectSlot(needsReasoning)

        // Loading/unloading a model also drops its native Conversation. Detect
        // that even when it happened from the Models screen, and rebuild the
        // system recap instead of silently starting with an old seed prompt.
        val engineEpoch = router.conversationEpoch(slot)
        if (activeEpochs[slot]?.let { it != engineEpoch } == true) {
            activeSystems.remove(slot)
            activeEpochs.remove(slot)
            injectedContexts.remove(slot)
        }

        // A persistent Conversation belongs to exactly one model. If routing
        // comes back to a model after the other brain answered, rebuild that
        // target from the app-level transcript; otherwise its private KV cache
        // would continue from an older, divergent conversation.
        if (lastConversationSlot != null && lastConversationSlot != slot && activeSystems.containsKey(slot)) {
            router.resetConversation(slot)
            activeSystems.remove(slot)
            activeEpochs.remove(slot)
            injectedContexts.remove(slot)
        }
        val injectedContext = injectedContexts.getOrPut(slot) { LinkedHashSet() }

        // The system instruction is built ONCE per conversation and then reused
        // verbatim. Rebuilding it every turn (it used to carry the retrieved
        // notes, which change with every question) forced the engine to recreate
        // the conversation, wiping the chat memory — the reason JARVIS could not
        // remember even the previous message.
        val system = activeSystems[slot] ?: buildSystem(notes).also {
            activeSystems[slot] = it
            activeEpochs[slot] = router.conversationEpoch(slot)
            injectedContext += notes
        }

        // Anything relevant that the model has not been shown yet rides along
        // with this turn instead of restarting the conversation.
        val fresh = notes.filterNot { it in injectedContext }
        injectedContext += fresh
        // Search the offline library BEFORE generating: the model answers from
        // passages it was given, not from what it half-remembers. When the
        // library holds nothing relevant, nothing is injected and the system
        // prompt's "say you don't know" rule applies unaided.
        val evidence = runCancellable { knowledgeEvidence(transcript) }.getOrNull()
        // Imported documents/attachments are searched on the same pre-generation
        // path as the offline library, so an answer about "this PDF" is grounded
        // in cited passages rather than improvised.
        val docEvidence = runCancellable { documents.documentEvidence(transcript) }.getOrNull()

        val message = buildString {
            if (conversationHint.isNotBlank()) {
                append("[Risultati verificati dal sistema da usare nella risposta: ")
                append(conversationHint.replace('\n', ' ').takeLast(COMPOUND_CONTEXT_CHARS))
                append("]\n")
            }
            if (docEvidence != null) {
                append("[Documenti importati pertinenti. Rispondi USANDO SOLO questo, ")
                append("e cita la fonte (nome file/pagina) fra parentesi. Se non basta, dillo.]\n")
                append(docEvidence)
                append('\n')
            }
            if (evidence != null) {
                append("[Documentazione offline pertinente. Rispondi USANDO SOLO questo, ")
                append("e cita la fonte fra parentesi. Se non basta, dillo.]\n")
                append(evidence)
                append('\n')
            }
            if (fresh.isNotEmpty()) {
                append("(dai miei appunti: ")
                append(fresh.joinToString(" · "))
                append(")\n")
            }
            append(transcript)
        }

        tryRemoteChat(message, system, needsReasoning)?.let { return it }

        val brain = if (slot == ModelSlot.ADVANCED) "avanzato" else "rapido"
        _diagnostic.value = if (retrieved.isEmpty()) {
            "penso ($brain)"
        } else {
            "memoria: ${retrieved.size} frammenti · $brain"
        }
        lastConversationSlot = slot
        val firstReply = try {
            router.chat(message, system, slot)
        } catch (e: CancellationException) {
            throw e
        } catch (_: LlmGenerationTimeoutException) {
            _diagnostic.value = "risposta fermata: tempo massimo"
            return TIMEOUT_REPLY
        }
        firstReply?.let(AssistantReplyCleaner::clean)?.ifBlank { null }?.let { return it }

        // Generation failed. The usual cause is an exhausted/corrupted KV cache
        // after a long chat, which a fresh conversation clears — so retry once
        // (losing the in-session history, not the vault memory) before giving up.
        Log.w(TAG, "llm_chat_null_retrying_fresh")
        _diagnostic.value = "riprovo con conversazione nuova"
        router.resetConversation(slot)
        activeSystems.remove(slot)
        activeEpochs.remove(slot)
        injectedContexts.remove(slot)
        val retryContext = LinkedHashSet<String>().also { it += notes }
        injectedContexts[slot] = retryContext
        val retrySystem = buildSystem(notes).also {
            activeSystems[slot] = it
            activeEpochs[slot] = router.conversationEpoch(slot)
        }
        val retryReply = try {
            router.chat(message, retrySystem, slot)
        } catch (e: CancellationException) {
            throw e
        } catch (_: LlmGenerationTimeoutException) {
            _diagnostic.value = "risposta fermata: tempo massimo"
            return TIMEOUT_REPLY
        }
        retryReply?.let(AssistantReplyCleaner::clean)?.ifBlank { null }?.let { return it }

        return "Il modello non è riuscito a rispondere. Prova «Nuova conversazione», " +
            "o ricarica il modello dalla schermata Modelli."
    }

    /**
     * Seeds a conversation: who JARVIS is, what day it is, the notes known now,
     * and — crucially — a recap of what was being said before the app was closed.
     * The engine's multi-turn memory is a KV cache that dies with the process, so
     * this recap is the only way the conversation can continue an hour later.
     */
    /**
     * If [transcript] is a durable personal fact and capture is on, returns an
     * offer to remember it (arming the confirming-write path so a "sì" saves it);
     * otherwise null so the turn continues normally. Deduplicated against what is
     * already in the vault and against facts already offered this conversation.
     */
    private suspend fun offerFactCapture(transcript: String): String? {
        if (!autoMemoryCapture) return null
        val candidate = com.simone.jarvismobile.core.memory.FactCapture.detect(transcript) ?: return null
        val key = candidate.fact.lowercase()
        if (!offeredFacts.add(key)) return null // already offered this session
        if (!runCancellable { memory.isConfigured() }.getOrDefault(false)) return null
        val known = runCancellable { memory.listRecords() }.getOrDefault(emptyList())
        if (known.any { it.text.equals(candidate.fact, ignoreCase = true) }) return null

        pendingConfirmation = com.simone.jarvismobile.core.protocol.ToolCall(
            id = java.util.UUID.randomUUID().toString(),
            name = "remember",
            arguments = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "text" to kotlinx.serialization.json.JsonPrimitive(candidate.fact),
                    "kind" to kotlinx.serialization.json.JsonPrimitive(candidate.kind.name),
                ),
            ),
            requiresConfirmation = true,
        )
        val marker = if (candidate.kind == com.simone.jarvismobile.core.memory.MemoryKind.SENSITIVE) {
            " (lo segno come dato sensibile 🔒)"
        } else {
            ""
        }
        return "Vuoi che me lo ricordi$marker? Posso salvare: «${candidate.fact}»."
    }

    private fun buildSystem(notes: List<String>): String = buildString {
        append(systemPrompt.trim())
        persona.trim().takeIf { it.isNotEmpty() }?.let {
            append("\n\nPERSONALITÀ (adotta questo carattere in ogni risposta):\n").append(it)
        }
        append("\n\nTi chiami ").append(assistantName)
        append(". Se Simone ti chiede il tuo nome, rispondi con questo.")

        val now = LocalDateTime.now()
        append("\n\nAdesso è ")
        append(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN)))
        append(".")

        if (notes.isNotEmpty()) {
            append("\n\nAPPUNTI DI SIMONE (usali quando sono pertinenti, senza citarli se non serve):\n")
            notes.forEach { append("- ").append(it).append('\n') }
        }

        val summary = conversationMemory.snapshot.value
        if (!summary.isEmpty) {
            append("\n\nMEMORIA BREVE STRUTTURATA DELLA CONVERSAZIONE ")
            append("(temporanea, non è nel vault):\n")
            append(summary.forPrompt())
            append('\n')
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
        processText(text, taskId = null)
    }

    /**
     * Durable-worker entry point. [taskId] makes chat persistence idempotent: if
     * Android stops the process after saving the user line, a WorkManager retry
     * resumes without duplicating that message or a reply already completed.
     */
    suspend fun processQueuedText(taskId: String, text: String): String? {
        restoreChat()
        _messages.value.firstOrNull { !it.fromUser && it.taskId == taskId }?.let { return it.text }
        return processText(text, taskId)
    }

    private suspend fun processText(text: String, taskId: String?): String? {
        // The chat mode picker prefixes the input with a focus tag; split it back
        // out so the user only ever sees their own words.
        val (focus, raw) = ChatFocus.parse(text)
        val message = raw.trim()
        if (message.isBlank()) return null
        return sessionMutex.withLock {
            _lastError.value = null
            _sending.value = true
            try {
                appendMessage(fromUser = true, text = message, taskId = taskId)
                _transcript.value = message
                _diagnostic.value = "chat scritta"
                val answer = if (focus == ChatFocus.ALL) {
                    generateAnswer(message)
                } else {
                    focusedAnswer(message, focus)
                }
                _reply.value = answer
                appendMessage(fromUser = false, text = answer, taskId = taskId)
                answer
            } catch (e: CancellationException) {
                _diagnostic.value = "risposta annullata"
                throw e
            } catch (e: Exception) {
                _lastError.value = "text_crash_${e.javaClass.simpleName}"
                _diagnostic.value = "CRASH ${e.javaClass.simpleName}"
                null
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Answers a question restricted to ONE source (the chat mode picker): the
     * user's memory, or the imported documents / offline library. It gathers only
     * that source, then — if a model is loaded — has it answer USING ONLY those
     * passages (never inventing); with no model it returns the passages plainly.
     * An empty source yields an honest "nothing found", not a guess.
     */
    private suspend fun focusedAnswer(query: String, focus: ChatFocus): String {
        val source = when (focus) {
            // Memoria + documenti importati.
            ChatFocus.MEMORY -> {
                val hits = runCancellable { memory.retrieveSmart(query, MEMORY_TOP_K) }.getOrDefault(emptyList())
                val mem = hits.joinToString("\n") { "• ${it.chunk.text.replace('\n', ' ').trim().take(500)}" }
                val docs = runCancellable { documents.documentEvidence(query) }.getOrNull().orEmpty()
                listOf(mem, docs).filter { it.isNotBlank() }.joinToString("\n\n")
            }
            // Solo la conoscenza offline (wiki/guide), non i documenti personali.
            ChatFocus.KNOWLEDGE -> runCancellable { knowledgeEvidence(query) }.getOrNull().orEmpty()
            ChatFocus.ALL -> ""
        }.trim()

        if (source.isBlank()) {
            return when (focus) {
                ChatFocus.MEMORY ->
                    "Non ho trovato nulla nella tua memoria o nei tuoi documenti su questo."
                else ->
                    "Non ho trovato nulla nella conoscenza offline importata."
            }
        }

        val heading = if (focus == ChatFocus.MEMORY) "Dalla tua memoria:" else "Dalla conoscenza:"
        if (llm.loadState.value != LlmLoadState.LOADED) return "$heading\n$source"

        val prompt = buildString {
            append("Rispondi alla domanda dell'utente USANDO SOLO le informazioni qui sotto. ")
            append("Non aggiungere nulla che non sia scritto qui; se non bastano, dillo con semplicità.")
            if (focus == ChatFocus.KNOWLEDGE) append(" Cita la fonte fra parentesi quando puoi.")
            append("\n\nInformazioni:\n").append(source)
            append("\n\nDomanda: ").append(query)
            append("\nRisposta:")
        }
        return runCancellable { llm.generate(prompt) }.getOrNull()
            ?.let(AssistantReplyCleaner::clean)?.ifBlank { null }
            ?: "$heading\n$source"
    }

    /**
     * Completes a tool call from the user's answer to JARVIS's own question
     * ("Per quanto tempo?" → "dieci minuti"). Returns null when the reply
     * doesn't contain the missing detail, so we never guess.
     */
    private fun fillSlot(
        pending: Pending,
        answer: String,
    ): Match? {
        fun build(vararg args: Pair<String, String>) =
            Match.Run(
                com.simone.jarvismobile.core.protocol.ToolCall(
                    id = java.util.UUID.randomUUID().toString(),
                    name = pending.tool,
                    arguments = kotlinx.serialization.json.JsonObject(
                        args.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) },
                    ),
                    requiresConfirmation = false,
                ),
            )

        val reply = answer.trim()
        return when (pending.tool) {
            // Complete either "Cosa?" or "Quando?" without losing fields that
            // were already extracted from the original calendar request.
            "add_reminder" -> when (pending.missing) {
                "text" -> {
                    if (reply.length < 2) return null
                    val partial = pending.args.toMutableMap().apply { put("text", reply.take(240)) }
                    if (partial["date"].isNullOrBlank()) {
                        Match.Ask(
                            "Per quale giorno devo segnare “${partial["text"]}”?",
                            "add_reminder",
                            "when",
                            partial,
                        )
                    } else {
                        build(*partial.entries.map { it.key to it.value }.toTypedArray())
                    }
                }
                "when" -> {
                    val note = pending.args["text"].orEmpty()
                    CommandMatcher.reminderFromAnswer(note, reply)
                }
                else -> null
            }

            // The title was missing; keep the list/starred that were understood.
            "add_task" -> if (reply.length < 2) {
                null
            } else {
                val partial = pending.args.toMutableMap().apply { put("text", reply.take(240)) }
                build(*partial.entries.map { it.key to it.value }.toTypedArray())
            }

            "complete_agenda" -> reply.takeIf { it.length >= 2 }
                ?.let { build("text" to it.take(200)) }

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
                        build(
                            "text" to "$note — $reply",
                            "kind" to pending.args["kind"].orEmpty().ifBlank { "PERMANENT" },
                        )
                    }
                }
                // We asked WHAT: the whole reply is the note.
                else -> reply.takeIf { it.length >= 3 }?.let {
                    build(
                        "text" to it,
                        "kind" to com.simone.jarvismobile.core.memory.MemoryStructure.classify(it).name,
                    )
                }
            }

            "create_calendar_event" -> {
                val partial = pending.args.toMutableMap()
                when (pending.missing) {
                    "title" -> {
                        if (reply.length < 2) return null
                        partial["title"] = reply.take(140)
                        if (partial["date"].isNullOrBlank()) {
                            Match.Ask(
                                "Per quale giorno devo preparare l'evento “${partial["title"]}”?",
                                "create_calendar_event",
                                "when",
                                partial,
                            )
                        } else {
                            build(*partial.entries.map { it.key to it.value }.toTypedArray())
                        }
                    }
                    "when" -> {
                        val parsed = ItalianDateTimeParser.parse(reply, LocalDateTime.now())
                        val date = parsed.date ?: return null
                        partial["date"] = date.toString()
                        parsed.time?.let {
                            partial["time"] = "%02d:%02d".format(it.hour, it.minute)
                        }
                        if (partial["title"].isNullOrBlank()) {
                            Match.Ask(
                                "Qual è il titolo dell'evento?",
                                "create_calendar_event",
                                "title",
                                partial,
                            )
                        } else {
                            build(*partial.entries.map { it.key to it.value }.toTypedArray())
                        }
                    }
                    else -> null
                }
            }

            "prepare_call" -> CommandMatcher.dialDraftCall("chiama $reply")

            "compose_sms" -> when (pending.missing) {
                "number" -> {
                    val body = pending.args["body"]
                    val rebuilt = if (body.isNullOrBlank()) {
                        "prepara un SMS al $reply"
                    } else {
                        "prepara un SMS al $reply: $body"
                    }
                    CommandMatcher.smsDraftCall(rebuilt)
                }
                "body" -> {
                    val number = pending.args["number"].orEmpty()
                    if (number.isBlank() || reply.length < 2) null
                    else CommandMatcher.smsDraftCall("prepara un SMS al $number: $reply")
                }
                else -> null
            }

            "navigate" -> reply.takeIf { it.length >= 2 }
                ?.let { build("destination" to it.take(240)) }

            "play_media" -> reply.takeIf { it.length >= 2 }
                ?.let { build("query" to it.take(240)) }

            "search_vault" -> reply.takeIf { it.length >= 2 }
                ?.let { build("query" to it.take(200)) }

            "open_app" -> CommandMatcher.appCall("apri $reply")

            "open_settings" -> CommandMatcher.settingsCall("apri le impostazioni $reply")

            else -> null
        }
    }

    /**
     * Passages from the offline library that support [question], already
     * formatted with their citation, or null when the library has nothing good
     * enough. Returning null is a feature: it is what lets JARVIS say it does
     * not know instead of filling the gap (docs/LOCAL_KNOWLEDGE.md).
     */
    private suspend fun knowledgeEvidence(question: String): String? {
        if (!knowledge.isConfigured()) return null
        knowledge.ensureBuilt()
        val found = knowledge.find(question, KNOWLEDGE_TOP_K)
        if (found !is Evidence.Grounded) return null
        _diagnostic.value = "documentazione: ${found.passages.size} passaggi"
        return found.passages.joinToString("\n") { p ->
            "- [" + p.chunk.citation + "] " + p.chunk.text.replace('\n', ' ').trim().take(KNOWLEDGE_CHARS)
        }
    }

    /**
     * Recognises and executes a Live Translator voice/chat command. Returns the
     * spoken confirmation, or null when the utterance isn't a translator command so
     * the normal pipeline continues. Starting the interpreter runs a separate
     * offline pipeline (STT → ML Kit translate → TTS) that never touches the LLM.
     */
    private suspend fun handleTranslatorCommand(transcript: String): String? {
        // The user is answering "Tra quali lingue?" from a previous turn.
        if (awaitingTranslatorLanguages) {
            val langs = LiveTranslatorCommands.languagesIn(transcript)
            if (langs.size >= 2) {
                awaitingTranslatorLanguages = false
                return startTranslator(langs[0], langs[1])
            }
            // Not two languages yet: keep waiting unless they clearly gave up.
            if (transcript.lowercase().trim().let { it in setOf("no", "niente", "lascia", "annulla", "stop") }) {
                awaitingTranslatorLanguages = false
                return "Va bene, nessuna traduzione."
            }
            return "Dimmi due lingue tra italiano, inglese, spagnolo, francese e giapponese. " +
                "Ad esempio «italiano e inglese»."
        }

        return when (val cmd = LiveTranslatorCommands.parse(transcript)) {
            is LiveTranslatorCommand.Start -> {
                // Copy to locals: cmd.source/target are :core properties and can't
                // be smart-cast across the module boundary after a null check.
                val src = cmd.source
                val tgt = cmd.target
                if (src == null || tgt == null) {
                    // No pair named: ask, and start on the next answer.
                    awaitingTranslatorLanguages = true
                    "Tra quali due lingue vuoi tradurre? " +
                        "Scegli tra italiano, inglese, spagnolo, francese e giapponese."
                } else {
                    startTranslator(src, tgt)
                }
            }
            LiveTranslatorCommand.Stop -> {
                if (!liveTranslator.isRunning) return null
                liveTranslator.stop()
                _diagnostic.value = "traduttore live fermato"
                "Ho fermato il traduttore live."
            }
            LiveTranslatorCommand.Swap -> {
                if (!liveTranslator.isRunning) return null
                liveTranslator.swap()
                "Ho invertito le lingue del traduttore."
            }
            null -> null
        }
    }

    /**
     * Starts a live-translation session between two languages in CONVERSAZIONE
     * mode and asks the UI to open the translator screen — a spoken request to
     * translate should land the user on the translator, not run it invisibly.
     */
    private suspend fun startTranslator(
        a: com.simone.jarvismobile.core.translate.TranslationLanguage,
        b: com.simone.jarvismobile.core.translate.TranslationLanguage,
    ): String {
        val session = runCatching {
            liveTranslatorRepo.buildSession(
                a = a,
                b = b,
                mode = com.simone.jarvismobile.core.translate.TranslationMode.CONVERSAZIONE,
            )
        }.getOrNull() ?: return "Non sono riuscito ad avviare il traduttore."
        liveTranslator.start(session)
        liveTranslator.requestOpenScreen()
        translatorTakingOver = true
        _diagnostic.value = "traduttore live avviato"
        return "Apro il traduttore tra ${session.languageA.display} e ${session.languageB.display}, " +
            "in modalità conversazione."
    }

    /**
     * Recognises an offline-navigation command and acts on it, returning the
     * spoken confirmation (or null when it isn't a navigation phrase, so the normal
     * pipeline continues). The deterministic `:core` parser turns the words into a
     * structured intent; the DESTINATION coordinate comes only from the offline
     * place index or a saved favourite — the model never invents one (spec §9).
     */
    private suspend fun handleNavigationCommand(transcript: String): String? {
        val intent = com.simone.jarvismobile.core.navigation.NavIntentParser.parse(transcript) ?: return null
        val here = navigation.fix.value?.location
        val regionId = navigation.coveringRegion.value?.id

        fun startTo(dest: com.simone.jarvismobile.core.navigation.LatLng, label: String, opts: com.simone.jarvismobile.core.navigation.RouteOptions): String {
            navigation.startNavigation(dest, opts)
            // Already looking at JARVIS Drive's own map (INTERNAL_JARVIS_NAVIGATION
            // Driving Mode) — it shares this same NavigationRepository, so the route
            // shows up there for free; popping the generic offline-nav overlay on
            // top of it would fight for the screen instead of helping.
            if (!drivingMode.state.value.active) navigation.requestOpenScreen()
            scope.launch { runCatching { placeSearch.addHistory(label, dest) } }
            return "Avvio la navigazione verso $label."
        }

        return when (intent) {
            is com.simone.jarvismobile.core.navigation.NavIntent.Stop -> {
                navigation.stopNavigation(); "Navigazione fermata."
            }
            is com.simone.jarvismobile.core.navigation.NavIntent.Pause -> {
                navigation.pauseNavigation(); "Navigazione in pausa."
            }
            is com.simone.jarvismobile.core.navigation.NavIntent.Resume -> {
                navigation.resumeNavigation(); "Riprendo la navigazione."
            }
            is com.simone.jarvismobile.core.navigation.NavIntent.Status -> {
                val prog = navigation.progress.value
                    ?: return "Non c'è una navigazione attiva."
                when (intent.kind) {
                    com.simone.jarvismobile.core.navigation.NavIntent.StatusKind.REMAINING_DISTANCE ->
                        "Mancano circa ${formatKm(prog.remainingDistanceMeters)}."
                    com.simone.jarvismobile.core.navigation.NavIntent.StatusKind.ETA ->
                        "Arrivo tra circa ${formatMinutes(prog.etaSeconds)}."
                }
            }
            is com.simone.jarvismobile.core.navigation.NavIntent.NavigateFavorite -> {
                val fav = placeSearch.favorites().firstOrNull { it.kind == intent.kind }
                    ?: return "Non hai ancora impostato «${favLabel(intent.kind)}». Puoi salvarlo dalla schermata di navigazione."
                startTo(fav.location, fav.label.ifBlank { favLabel(intent.kind) }, com.simone.jarvismobile.core.navigation.RouteOptions())
            }
            is com.simone.jarvismobile.core.navigation.NavIntent.Navigate -> {
                // Saved place -> explicit coordinate -> offline search -> ranking ->
                // destination (spec §9). NavIntentParser already routed a bare
                // favourite phrase to NavigateFavorite above, so what lands here is
                // free text: an explicit "lat,lon" or a name/address to search.
                val explicit = com.simone.jarvismobile.core.navigation.DestinationResolver
                    .resolve(intent.query, favorites = emptyList(), searchCandidates = emptyList(), origin = here)
                if (explicit is com.simone.jarvismobile.core.navigation.ResolvedDestination.Found &&
                    explicit.source == com.simone.jarvismobile.core.navigation.DestinationSource.EXPLICIT_COORDINATE
                ) {
                    startTo(explicit.place.location, explicit.place.name, intent.options)
                } else {
                    val hits = placeSearch.search(intent.query, here, limit = 5)
                    when (val resolved = com.simone.jarvismobile.core.navigation.DestinationResolver.decide(hits)) {
                        is com.simone.jarvismobile.core.navigation.ResolvedDestination.Found ->
                            startTo(resolved.place.location, resolved.place.name, intent.options)
                        is com.simone.jarvismobile.core.navigation.ResolvedDestination.Ambiguous -> {
                            pendingDestinationPick = PendingDestinationPick(resolved.candidates, intent.options)
                            "Ho trovato più risultati: ${describeCandidates(resolved.candidates)}. Quale intendi?"
                        }
                        is com.simone.jarvismobile.core.navigation.ResolvedDestination.NotFound,
                        is com.simone.jarvismobile.core.navigation.ResolvedDestination.OutOfCoverage,
                        -> if (regionId == null) {
                            "Non ho una mappa offline per questa zona. Puoi installarne una da Impostazioni → " +
                                "Navigazione offline → Mappe offline, oppure di' \"apri Google Maps\"."
                        } else {
                            "Non ho trovato «${intent.query}» nelle mappe offline installate."
                        }
                    }
                }
            }
            is com.simone.jarvismobile.core.navigation.NavIntent.NavigateNearest -> {
                if (here == null || regionId == null) return "Non ho ancora la posizione GPS o la mappa di questa zona."
                val hit = placeSearch.nearby(intent.category, here, regionId)
                    ?: return "Non trovo un punto della categoria richiesta qui vicino."
                startTo(hit.place.location, hit.place.name, com.simone.jarvismobile.core.navigation.RouteOptions())
            }
            is com.simone.jarvismobile.core.navigation.NavIntent.SearchNearby -> {
                if (here == null || regionId == null) return "Non ho ancora la posizione o la mappa di questa zona."
                val hit = placeSearch.nearby(intent.category, here, regionId)
                    ?: return "Non trovo nulla di simile qui vicino."
                "Il più vicino è ${hit.place.name}${hit.distanceMeters?.let { ", a ${formatKm(it)}" } ?: ""}."
            }
            is com.simone.jarvismobile.core.navigation.NavIntent.AddWaypoint,
            is com.simone.jarvismobile.core.navigation.NavIntent.SetAvoid,
            -> null // handled inside the navigation screen for an active route
        }
    }

    private fun describeCandidates(hits: List<com.simone.jarvismobile.core.navigation.PlaceHit>): String =
        hits.take(3).joinToString(" e ") { hit ->
            val place = hit.place
            if (place.address.isNotBlank()) "${place.name} a ${place.address}" else place.name
        }

    /**
     * Picks among ambiguous destination [candidates] from the user's follow-up
     * reply: an ordinal ("il primo") or, more naturally here since the question
     * already named each candidate's town/address, whichever wording best
     * matches one of them (re-ranked against just this short candidate set with
     * the same [com.simone.jarvismobile.core.navigation.PlaceSearchRanker] the
     * original search used). Returns null when the reply still doesn't clearly
     * pick one, so the caller can ask again instead of guessing.
     */
    private fun pickDestination(
        candidates: List<com.simone.jarvismobile.core.navigation.PlaceHit>,
        reply: String,
    ): com.simone.jarvismobile.core.navigation.Place? {
        val normalized = com.simone.jarvismobile.core.navigation.ItalianTextNormalizer.normalize(reply)
        DESTINATION_ORDINALS.entries.firstOrNull { (word, _) ->
            Regex("""(?<!\p{L})$word(?!\p{L})""").containsMatchIn(normalized)
        }?.let { (_, index) -> candidates.getOrNull(index)?.place?.let { return it } }

        val reRanked = com.simone.jarvismobile.core.navigation.PlaceSearchRanker.rank(
            reply, candidates.map { it.place }, navigation.fix.value?.location, limit = 1,
        )
        return reRanked.firstOrNull()?.place
    }

    private fun favLabel(kind: com.simone.jarvismobile.core.navigation.FavoriteKind): String = when (kind) {
        com.simone.jarvismobile.core.navigation.FavoriteKind.HOME -> "Casa"
        com.simone.jarvismobile.core.navigation.FavoriteKind.WORK -> "Lavoro"
        com.simone.jarvismobile.core.navigation.FavoriteKind.CUSTOM -> "Preferito"
    }

    private fun formatKm(meters: Double): String =
        if (meters >= 1000) String.format(java.util.Locale.ITALY, "%.1f chilometri", meters / 1000.0)
        else "${(meters / 10).toInt() * 10} metri"

    private fun formatMinutes(seconds: Double): String {
        val m = (seconds / 60).toInt()
        return if (m >= 60) "${m / 60} ore e ${m % 60} minuti" else "$m minuti"
    }

    /** Indexes the offline library in the background if one is configured. */
    suspend fun ensureKnowledgeReady() = knowledge.ensureBuilt()

    /**
     * Resolves a yes/no to an offer JARVIS made itself (tick an item off, set an
     * alert). Returns the reply when the utterance answered one, else null so the
     * turn continues normally.
     */
    private suspend fun answerPendingOffer(transcript: String): String? {
        val answer = transcript.lowercase().trim().trim('.', '!', ',')
        val firstWord = answer.substringBefore(' ')
        val yes = firstWord in CONFIRM_WORDS || CONFIRM_WORDS.any { answer.startsWith(it) }
        val no = firstWord in DECLINE_WORDS

        pendingCompletion?.let { entry ->
            pendingCompletion = null
            if (yes) {
                val done = runCatching { agenda.setDone(entry.id, true) }.getOrNull()
                return if (done != null) "Fatto, ho segnato “${done.text}” come completata."
                else "Non sono riuscito a segnarla: controlla in Agenda."
            }
            if (no) return "Va bene, la lascio aperta."
            // Neither: drop the offer and treat this as a fresh request.
        }

        pendingAlertFor?.let { id ->
            pendingAlertFor = null
            if (yes) {
                val alerts = listOf(
                    com.simone.jarvismobile.core.agenda.ReminderAlert(
                        com.simone.jarvismobile.core.agenda.ReminderAlertType.AT_TIME,
                    ),
                )
                val updated = runCatching { agenda.updateAlerts(id, alerts) }.getOrNull()
                return if (updated != null) "Ti avviso all'ora dell'impegno. Puoi cambiare l'anticipo in Agenda."
                else "Non sono riuscito a impostare l'avviso: puoi farlo dall'Agenda."
            }
            if (no) return "Va bene, nessun avviso."
        }
        return null
    }

    /**
     * Dismisses any "risposta pronta" notification still in the shade.
     *
     * setAutoCancel only fires when the notification itself is tapped, so opening
     * the chat from inside the app left the entry sitting there for a message the
     * user was already reading. Only the response range is touched, never the
     * foreground-service notification.
     */
    fun clearResponseNotifications() {
        runCatching {
            val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return
            nm.activeNotifications
                .map { it.id }
                .filter { it in RESPONSE_NOTIFICATION_MIN..RESPONSE_NOTIFICATION_MAX }
                .forEach { nm.cancel(it) }
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
        loadClassifierIfConfigured()
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
        if (path == settings.modelPath.first()) {
            // The fast engine already owns this exact model. Loading it again in
            // the advanced slot doubles RAM and warm-up time without improving
            // quality; complex turns automatically fall back to the fast slot.
            router.advanced.unload()
            settings.clearAdvancedModel()
            _diagnostic.value = "un solo modello: rapido e complesso coincidono"
            return
        }
        val file = File(path)
        if (!file.exists()) return
        val name = settings.advancedModelName.first().ifBlank { file.name }
        _diagnostic.value = "carico modello avanzato…"
        router.advanced.load(path, name)
    }

    /**
     * Loads the optional third model dedicated to intent classification (see
     * `LlmRouter.classifierEngine`). Never used for an actual answer, so it is
     * safe to keep tiny; the app works exactly as before (classification falls
     * back to the fast engine) when none is configured.
     */
    private suspend fun loadClassifierIfConfigured() {
        if (router.classifierLoadState.value == LlmLoadState.LOADED) return
        val path = settings.classifierModelPath.first()
        if (path.isBlank()) return
        if (path == settings.modelPath.first()) {
            // The fast engine already owns this exact model — loading it again
            // just for classification would double RAM for no benefit.
            router.classifier.unload()
            settings.clearClassifierModel()
            _diagnostic.value = "un solo modello: rapido e classificatore coincidono"
            return
        }
        val file = File(path)
        if (!file.exists()) return
        val name = settings.classifierModelName.first().ifBlank { file.name }
        _diagnostic.value = "carico modello classificatore…"
        router.classifier.load(path, name)
    }

    fun newConversation() {
        router.resetConversation()
        pendingConfirmation = null
        pendingSlot = null
        pendingPick = null
        pendingDestinationPick = null
        lastAgendaEntryId = null
        awaitingAnswer = false
        awaitingTranslatorLanguages = false
        activeSystems.clear()
        activeEpochs.clear()
        injectedContexts.clear()
        lastConversationSlot = null
        bargeInRequested = false
        offeredFacts.clear()
        _messages.value = emptyList()
        _transcript.value = ""
        _reply.value = ""
        _diagnostic.value = "nuova conversazione"
        scope.launch {
            chatStore.clear()
            conversationMemory.clear()
            // Pick up a persona / capture change made in Impostazioni.
            persona = runCatching { settings.personaPrompt.first() }.getOrDefault(SettingsRepository.DEFAULT_PERSONA)
            autoMemoryCapture = runCatching { settings.autoMemoryCapture.first() }.getOrDefault(true)
        }
    }

    private suspend fun speakOut(text: String) {
        if (tts.ensureReady()) {
            tts.speak(expressiveText(text))
        } else {
            _diagnostic.value = "${_diagnostic.value} | tts_unavailable [${tts.lastDetail.value}]"
        }
    }

    /**
     * Chooses how the reply is delivered and returns the text to speak with any
     * hidden expressive metadata stripped. When "Espressività automatica" is on
     * the tone follows the reply's meaning (metadata tag if the model emitted one,
     * otherwise a light heuristic); when off, a fixed style is applied. Either way
     * the chosen [SpeechStyle] is pushed to the engine before it speaks, so the
     * same voice varies interpretation, never identity.
     */
    private suspend fun expressiveText(text: String): String {
        val base = SpeechStyle(
            rate = runCatching { settings.ttsSpeechRate.first() }.getOrDefault(SpeechStyle.NATURALE.rate),
            pitch = runCatching { settings.ttsPitch.first() }.getOrDefault(SpeechStyle.NATURALE.pitch),
            pauseScale = runCatching { settings.ttsPauseScale.first() }.getOrDefault(SpeechStyle.NATURALE.pauseScale),
            expressiveness = runCatching { settings.ttsExpressiveness.first() }
                .getOrDefault(SpeechStyle.NATURALE.expressiveness),
        )
        val auto = runCatching { settings.autoExpressive.first() }.getOrDefault(true)
        val intensity = when (runCatching { settings.expressiveIntensity.first() }.getOrDefault("media").lowercase()) {
            "bassa" -> ExpressivePlanner.LOW
            "alta" -> ExpressivePlanner.HIGH
            else -> ExpressivePlanner.MEDIUM
        }
        val forced = if (auto) {
            null
        } else {
            Emotion.fromTag(runCatching { settings.expressiveManualStyle.first() }.getOrDefault("naturale"))
                ?: Emotion.WARM
        }
        val plan = ExpressivePlanner.plan(
            reply = text,
            base = base,
            intensityScale = intensity,
            userContext = recentConversationContext(),
            forcedEmotion = forced,
        )
        runCatching { tts.setStyle(plan.style) }
        return plan.cleanedText
    }

    /** Speaks a worker result only when the user explicitly enabled the option. */
    suspend fun speakBackgroundResponse(text: String): Boolean {
        if (!settings.speakBackgroundResponses.first()) return false
        // Never flush speech belonging to a live voice conversation or another
        // worker. The text answer/notification remains the authoritative result.
        if (state.value != ConversationState.Idle || tts.state.value == TtsState.SPEAKING) return false
        if (!tts.ensureReady()) return false
        tts.speak(expressiveText(text).take(MAX_BACKGROUND_SPEECH_CHARS))
        return true
    }

    suspend fun refreshVoices(): List<TtsVoiceOption> = tts.refreshVoices()

    suspend fun configureVoice(name: String?, rate: Float, pitch: Float): Boolean =
        tts.configure(name, rate, pitch)

    /** Stops the current sentence and lets the active visible session listen now. */
    fun interruptAndListen() {
        if (state.value == ConversationState.Speaking || tts.state.value == TtsState.SPEAKING) {
            bargeInRequested = true
            tts.stop()
            _diagnostic.value = "interruzione vocale…"
        } else {
            cancel()
        }
    }

    /**
     * Silences the current utterance without cancelling the turn. Used when the
     * user switches spoken replies off while JARVIS is mid-sentence.
     */
    fun stopSpeaking() {
        tts.stop()
    }

    fun cancel() {
        bargeInRequested = false
        stt.cancel()
        audioCapture.cancel()
        tts.stop()
        router.cancel()
        // Stop the running turn itself, not just the recognizer, so it unwinds out
        // of stt.transcribe() and releases sessionMutex — otherwise the next press
        // sees the mutex still locked and the orb stays on "Pronto".
        //
        // sessionJob is deliberately NOT nulled out here. Nulling it immediately
        // used to race a rapid stop-then-restart: startSession() reads sessionJob
        // as `previous` to cancelAndJoin() before starting the new turn, and a
        // premature null made it skip that wait entirely, so the new turn could
        // start while the old one was still unwinding and find sessionMutex still
        // locked — the orb looked stopped but silently ignored the restart tap.
        // invokeOnCompletion clears the reference once the coroutine has actually
        // finished (guarded so it never clobbers a newer job a restart already
        // installed in the meantime).
        val cancelled = sessionJob
        cancelled?.cancel()
        cancelled?.invokeOnCompletion { if (sessionJob === cancelled) sessionJob = null }
        machine.dispatch(ConversationEvent.CancelRequested)
    }

    /** Stops only the active written-answer inference — local or, if one is in flight, remote (§ tryRemoteChat). */
    fun cancelTextGeneration() {
        router.cancel()
        remoteChatState.activeRequestId?.let { remoteAiEngine.cancel(it) }
        _diagnostic.value = "annullamento risposta…"
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

    /**
     * Speaks a sample that exercises the shaping rules — a full stop, a comma, a
     * time and a percentage — so the chosen delivery can be judged by ear right
     * after moving a slider, instead of on the next real reply.
     */
    suspend fun previewVoice(): Boolean {
        if (!tts.ensureReady()) { _lastError.value = "tts_unavailable"; return false }
        tts.speak(VOICE_PREVIEW)
        return true
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

        /**
         * How long [startSession] waits for a just-cancelled previous turn to
         * actually finish before starting the new one regardless. A blocking
         * native LLM call cannot be interrupted by coroutine cancellation alone
         * (only [com.simone.jarvismobile.llm.LlmEngine.cancel]'s native
         * cancelProcess() can, and its own latency is outside this app's
         * control) — an unbounded `cancelAndJoin()` here used to mean a new
         * session could never start until that orphaned call finished on its
         * own, however long that took, even though the visible state had
         * already reset to "Pronto". See [runSession]'s still-locked branch for
         * what happens if this bound is hit.
         */
        const val SESSION_HANDOFF_TIMEOUT_MS = 4_000L

        /**
         * How long the orb shows a transient error before it clears itself back to
         * Idle — long enough to see «ERRORE» and hear a short spoken hint, short
         * enough that the wake word resumes quickly.
         */
        private const val ERROR_AUTO_RESET_MS = 4_000L

        /** Id range used by AssistantTaskWorker for "response ready" notifications. */
        private const val RESPONSE_NOTIFICATION_MIN = 3_000
        private const val RESPONSE_NOTIFICATION_MAX = 3_000 + 0x0fff

        /** Tail of the reminder reply that offers to set an alert. */
        const val ALERT_OFFER = "Vuoi che ti avvisi?"

        /** Sample used by the Settings voice preview. */
        const val VOICE_PREVIEW =
            "Buonasera Simone. Alle 15:30 hai la revisione dell'auto, " +
                "e la batteria è al 68%. Vuoi che ti ricordi altro?"

        /** Safety cap on consecutive hands-free exchanges before requiring a press. */
        const val MAX_FOLLOW_UPS = 8

        /** How many library passages may ground one answer. */
        const val KNOWLEDGE_TOP_K = 3

        /** Cap per passage, so evidence cannot crowd out the conversation. */
        const val KNOWLEDGE_CHARS = 700

        /** How many vault chunks to inject as grounding context per question. */
        const val MEMORY_TOP_K = 4

        /** How many past lines are recapped to the model when a chat resumes. */
        const val RECAP_MESSAGES = 16

        /** Cap per recapped line, so a long answer can't eat the context window. */
        const val RECAP_CHARS = 320

        /** Small context window used only for intent/coreference classification. */
        const val CONTEXT_MESSAGES = 6
        const val CONTEXT_LINE_CHARS = 240
        const val CLASSIFIER_CONTEXT_CHARS = 1_200

        /** Bound context shared among independently routed parts of one message. */
        const val COMPOUND_CONTEXT_CHARS = 1_200

        const val TIMEOUT_REPLY =
            "La risposta si è bloccata e l'ho fermata automaticamente. Prova a riformulare la richiesta."

        const val MAX_BACKGROUND_SPEECH_CHARS = 1_500

        private val SAFE_COMPOUND_TOOLS = setOf(
            "get_time", "time_until", "battery_status", "list_agenda", "list_memories", "calculate",
        )

        /** Affirmative replies that approve a pending tool confirmation. */
        private val CONFIRM_WORDS = listOf("sì", "si", "certo", "conferma", "ok", "va bene", "procedi", "yes")

        /** Replies that cancel a pending tool confirmation. */
        private val DECLINE_WORDS = listOf("no", "annulla", "lascia", "niente", "ferma", "stop")

        /** Ordinal words for picking among ambiguous destination candidates. */
        private val DESTINATION_ORDINALS = mapOf(
            "primo" to 0, "prima" to 0, "uno" to 0,
            "secondo" to 1, "seconda" to 1, "due" to 1,
            "terzo" to 2, "terza" to 2, "tre" to 2,
        )
        private const val TAG = "JarvisSession"
    }
}
