package com.simone.jarvismobile.engine

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.ai.AiRequest
import com.simone.jarvismobile.ai.AiRoutingContextProvider
import com.simone.jarvismobile.ai.LastRemoteAttempt
import com.simone.jarvismobile.ai.RemoteAiEngine
import com.simone.jarvismobile.ai.RemoteAttemptOutcome
import com.simone.jarvismobile.ai.RemoteChatState
import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.ai.AiRoutingHeuristic
import com.simone.jarvismobile.core.engine.BrainEvent
import com.simone.jarvismobile.core.engine.BrainReply
import com.simone.jarvismobile.core.engine.ReasoningMode
import com.simone.jarvismobile.core.engine.SentenceStream
import com.simone.jarvismobile.core.protocol.AssistantResponse
import com.simone.jarvismobile.core.protocol.ParseResult
import com.simone.jarvismobile.core.protocol.ResponseParser
import com.simone.jarvismobile.core.routing.ComplexityHeuristic
import com.simone.jarvismobile.core.tools.RelevantToolSelector
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.DEFAULT_GENERATION_TIMEOUT_SECONDS
import com.simone.jarvismobile.llm.LlmRouter
import com.simone.jarvismobile.llm.ModelSlot
import com.simone.jarvismobile.tools.ToolRunner
import com.simone.jarvismobile.util.runCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `ConversationalJarvisEngine`'s reasoning core (spec §3). Turns one user turn
 * (plus whatever `ContextAssembler` has already assembled) into a single
 * structured [AssistantResponse] — never free text parsed with regex when a
 * structured contract is available, and never a direct call into
 * [ToolRunner]/`ToolRegistry`: [tools] is read here ONLY for
 * [ToolRunner.available] (the live tool catalog for the prompt), the same
 * read-only introspection `ProModeCoordinator` already does — actually
 * executing a tool call this brain returns is `ToolRouter`'s job, one layer
 * up in `ConversationalJarvisEngine`. That separation is what keeps "LLM
 * reasoning" and "tool execution" from ever sharing a call stack.
 *
 * The underlying [LlmRouter]/`LitertLmEngine` stack has no token-streaming
 * API (see `BrainEvent`'s doc comment) — [reply] is a single blocking call;
 * [replyEvents] is the honest, already-real post-hoc chunking of a completed
 * reply into incremental UI/TTS-ready events.
 *
 * **Tries JARVIS Core before the local model, same as `SessionCoordinator`'s
 * Classico path (§ FASE SUCCESSIVA — integrazione Motore Conversazionale)**:
 * an earlier round of this integration deliberately did NOT route this
 * brain to Core, because `jarvis-protocol/main` v1.0.0's `JarvisCoreRequest`
 * had no field Core actually read for a per-request system prompt — Core
 * would answer with its own unrelated default persona, never seeing
 * [PROTOCOL_BLOCK] or the live tool catalog, so [ResponseParser] would treat
 * every Core reply as [ParseResult.PlainText] and tool-calling would go
 * silently dark for any turn Core happened to answer. That gap is now
 * closed: `jarvis-protocol/main` v1.1.0 added `JarvisRequest.systemPrompt`
 * (optional, additive — see that repo's CHANGELOG), `jarvis-core`'s
 * `RequestOrchestrator` now forwards it to the resolved provider instead of
 * its own fixed default, and [RemoteAiEngine] threads
 * [AiRequest.systemPrompt] through to it — so [tryRemoteReply] can hand
 * Core the EXACT SAME [systemPromptFor] result (persona + protocol block +
 * whichever tools that turn selected — see its own doc comment, § FASE
 * 2A.2) the local model gets, and [parser] parses whichever one answered
 * identically. [reply] tries Core first via [tryRemoteReply] on every call —
 * i.e. every round of a multi-round tool loop independently, not just the
 * first — reusing the exact same [AiRoutingHeuristic]/[AiRoutingContextProvider]/
 * [RemoteAiEngine] `SessionCoordinator.tryRemoteChat` already uses (§ "NON
 * creare un secondo router/client/pipeline" — this is the same one client,
 * a second call site). On any recoverable failure (Core disabled/offline/
 * degraded/timeout/network/empty reply) it returns `null` and [reply] falls
 * through to the existing, byte-for-byte unmodified `router.chat(...)` call
 * — with `remoteAiEnabled=false` (default) [tryRemoteReply] always returns
 * `null` immediately, so local-only behaviour is unchanged from before this
 * round. **Honest limit, not fixable without a real remote model to test
 * against**: whether Core's chosen model actually FOLLOWS [PROTOCOL_BLOCK]'s
 * strict-JSON instruction is a property of that model, not of this wiring —
 * a Core answer that ignores it still degrades gracefully to plain
 * `assistant_text` (never a crash), it just means tool-calling doesn't fire
 * for that specific round, exactly as an unparsable local reply already
 * behaves today.
 */
@Singleton
class JarvisBrain @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: LlmRouter,
    private val tools: ToolRunner,
    private val remoteAiEngine: RemoteAiEngine,
    private val routingContext: AiRoutingContextProvider,
    private val remoteChatState: RemoteChatState,
    private val settings: SettingsRepository,
) {
    private val parser = ResponseParser()

    /** Resolves which model slot answers this turn, from the reasoning-mode setting. */
    fun resolveSlot(mode: ReasoningMode, text: String): ModelSlot = when (mode) {
        ReasoningMode.FAST -> ModelSlot.FAST
        ReasoningMode.DEEP -> if (router.hasAdvanced) ModelSlot.ADVANCED else ModelSlot.FAST
        ReasoningMode.AUTO -> router.selectSlot(ComplexityHeuristic.needsReasoning(text))
    }

    /**
     * One model turn. [userText] is the raw user message; [contextBlock] is
     * whatever `ContextAssembler` decided is worth including (memory,
     * pending-task state, prior tool results) — appended, never silently
     * merged into [userText], so a caller can log/inspect them separately.
     * Returns null only when the model itself is unavailable. [timeoutSeconds]
     * bounds this one native call — see [com.simone.jarvismobile.llm.LlmEngine.chat].
     * A follow-up "compose the final answer from these tool results" round only
     * needs to phrase already-known text, so callers should pass a shorter
     * budget there than the full [DEFAULT_GENERATION_TIMEOUT_SECONDS] a first,
     * real-reasoning round needs.
     */
    suspend fun reply(
        userText: String,
        contextBlock: String,
        slot: ModelSlot,
        timeoutSeconds: Long = DEFAULT_GENERATION_TIMEOUT_SECONDS,
    ): BrainReply {
        // § logging temporaneo obbligatorio, audit "Conversational mode non
        // tenta più Core dopo integrazione" — prova che reply() sia stato
        // raggiunto davvero per questo turno, prima di qualunque altra cosa.
        Log.i(TAG, "BRAIN_REPLY_ENTER slot=$slot")
        val prompt = if (contextBlock.isBlank()) userText else "$contextBlock\n\n$userText"
        val turnSystemPrompt = systemPromptFor(userText)
        val remote = tryRemoteReply(prompt, turnSystemPrompt, slot, timeoutSeconds)
        if (remote == null) Log.i(TAG, "BRAIN_FALLBACK_LOCAL")
        val raw = remote
            ?: router.chat(prompt, turnSystemPrompt, slot, timeoutSeconds)
            ?: return BrainReply.Unavailable
        return when (val parsed = parser.parse(raw)) {
            is ParseResult.Valid -> BrainReply.Ready(parsed.response, parsedCleanly = true)
            is ParseResult.Repaired -> BrainReply.Ready(parsed.response, parsedCleanly = true)
            // Never treated as tool calls — ResponseParser's contract already
            // guarantees invalid/unrepairable JSON never reaches a caller as
            // anything but plain text.
            is ParseResult.PlainText -> BrainReply.Ready(
                AssistantResponse(assistantText = parsed.rawText.trim()),
                parsedCleanly = false,
            )
        }
    }

    /**
     * See the class doc comment for the full architecture. Mirrors
     * `SessionCoordinator.tryRemoteChat`'s pattern exactly (decide → build
     * [AiRequest] → call [RemoteAiEngine] → null on any recoverable failure)
     * but maps [slot] to [AiRequestType] instead of `needsReasoning`, since
     * that is what this caller already resolved via [resolveSlot]. Returns
     * the RAW reply text — parsing happens once, uniformly, back in [reply]
     * — never [AssistantReplyCleaner]-style cleanup, which is specific to
     * Classico's plain human-facing text, not this brain's JSON contract.
     */
    private suspend fun tryRemoteReply(
        prompt: String,
        systemPromptText: String,
        slot: ModelSlot,
        timeoutSeconds: Long,
    ): String? {
        Log.i(TAG, "BRAIN_TRY_REMOTE_ENTER")
        val requestType = if (slot == ModelSlot.ADVANCED) AiRequestType.COMPLEX else AiRequestType.CHAT
        val prefs = routingContext.preferencesFor(requestType)
        val decision = AiRoutingHeuristic.decide(requestType, prefs)
        Log.i(TAG, "BRAIN_ROUTE target=${decision.target} reason=${decision.reason}")

        // § audit "tryRemoteReply non arriva alla chiamata HTTP": snapshot the
        // raw toggles/endpoint alongside the derived decision — settling
        // whether coreEnabled/remoteAiEnabled genuinely came from the same
        // SettingsRepository the UI reads (they do: same instance, same
        // DataStore keys) is then a read of this state, not another guess.
        val coreEnabledNow = settings.coreEnabled.first()
        val preferredRemoteNow = settings.corePreferRemote.first()
        val endpointNow = runCatching { remoteAiEngine.describeEndpoint() }.getOrNull()
        fun record(outcome: RemoteAttemptOutcome, failureReason: String? = null) {
            remoteChatState.recordAttempt(
                LastRemoteAttempt(
                    engine = "Conversazionale",
                    requestType = requestType.name,
                    target = decision.target.name,
                    reason = decision.reason,
                    coreState = prefs.coreState.name,
                    coreEnabled = coreEnabledNow,
                    remoteAiEnabled = prefs.remoteAiEnabled,
                    preferredRemote = preferredRemoteNow,
                    outcome = outcome,
                    failureReason = failureReason,
                    endpoint = endpointNow,
                    endpointPath = "/v1/chat",
                ),
            )
        }

        if (decision.target == AiExecutionTarget.LOCAL) {
            Log.i(TAG, "CHAT -> LOCAL (reason=${decision.reason}, engine=conversazionale)")
            record(RemoteAttemptOutcome.NOT_ATTEMPTED)
            remoteChatState.setLastRoute("LOCAL (${decision.reason})")
            return null
        }

        val targetLabel = if (decision.target == AiExecutionTarget.REMOTE_BRAIN) "CORE BRAIN" else "CORE FAST"
        val requestId = java.util.UUID.randomUUID().toString()
        val request = AiRequest(
            requestId = requestId,
            text = prompt,
            systemPrompt = systemPromptText,
            requestType = requestType,
            timeoutSeconds = timeoutSeconds,
            preferredModel = if (decision.target == AiExecutionTarget.REMOTE_BRAIN) "brain" else null,
        )
        remoteChatState.activeRequestId = requestId
        Log.i(TAG, "BRAIN_REMOTE_START requestId=$requestId target=${decision.target}")
        record(RemoteAttemptOutcome.STARTED)
        val result = try {
            runCancellable { remoteAiEngine.generate(request) }.getOrNull()
        } finally {
            remoteChatState.activeRequestId = null
        }
        if (result == null || !result.success) {
            // § audit "ENGINE_ERROR non mostra la causa reale": the bare
            // AiFailureReason enum (e.g. "ENGINE_ERROR") never said WHERE a
            // failure happened before the request even reached Core — append
            // result.errorDetail (JarvisCoreClientImpl's phase-tagged real
            // exception class/message/endpoint) whenever present, so this
            // reads like "ENGINE_ERROR: http:ConnectException: ... @ ..."
            // instead of stopping at the enum name.
            val reason = (result?.failureReason?.name ?: "cancelled_or_null") +
                (result?.errorDetail?.let { ": $it" } ?: "")
            Log.i(TAG, "BRAIN_REMOTE_FAIL reason=$reason")
            Log.i(TAG, "CORE FAILED -> LOCAL FALLBACK (reason=$reason, engine=conversazionale)")
            record(RemoteAttemptOutcome.FAILED, failureReason = reason)
            remoteChatState.setLastRoute("LOCAL (fallback dopo Core: $reason)")
            return null
        }
        val text = result.text?.takeIf { it.isNotBlank() }
        if (text == null) {
            Log.i(TAG, "BRAIN_REMOTE_FAIL reason=empty_reply")
            Log.i(TAG, "CORE FAILED -> LOCAL FALLBACK (reason=empty_reply, engine=conversazionale)")
            record(RemoteAttemptOutcome.FAILED, failureReason = "empty_reply")
            remoteChatState.setLastRoute("LOCAL (fallback dopo Core: empty_reply)")
            return null
        }
        Log.i(TAG, "BRAIN_REMOTE_SUCCESS target=${decision.target}")
        record(RemoteAttemptOutcome.SUCCESS)
        Log.i(TAG, "CHAT -> $targetLabel (engine=conversazionale)")
        remoteChatState.setLastRoute(targetLabel)
        return text
    }

    /** Post-hoc sentence-chunked delivery of an already-produced [response]. */
    fun replyEvents(response: AssistantResponse): List<BrainEvent> = SentenceStream.from(response)

    /**
     * Built once per process, like `ProModeCoordinator.systemPrompt`: the same
     * persona asset plus a protocol block, cached — only the tool catalog
     * appended after it varies per turn (see [systemPromptFor]). Distinct
     * instructions from Pro mode's block: this brain is expected to hold
     * multi-turn state and ask clarifying questions
     * ([AssistantResponse.followUpExpected]), which Pro mode's single-shot
     * protocol never uses.
     */
    private val personaAndProtocol: String by lazy {
        val persona = runCatching {
            context.assets.open("prompts/jarvis_system_it.md").bufferedReader().use { it.readText() }
        }.getOrDefault("Sei JARVIS, un assistente personale offline. Rispondi in italiano, breve e naturale.")
        buildString {
            append(persona.trim())
            append("\n\n")
            append(PROTOCOL_BLOCK)
        }
    }

    /**
     * § FASE 2A.2 — root cause of the ~72s/~26s FAST latencies measured in
     * FASE 2A.1: this catalog used to embed all ~53 registered tools
     * unconditionally on every turn, pushing the system prompt past
     * `jarvis-protocol`'s 8000-char wire limit and forcing Ollama to
     * re-evaluate ~2000+ prompt tokens even for a plain "Ciao". Now
     * [RelevantToolSelector] decides — deterministically, no second LLM —
     * which of [ToolRunner.available]'s live tools are plausibly relevant to
     * [userText] before this prompt is built, so a simple conversational turn
     * gets none of them and a "torcia"/"agenda"/"memoria"-shaped turn gets
     * only its own family. [ToolRegistry] itself, tool execution and
     * argument validation are entirely untouched — this only decides what
     * the model is TOLD about, never what it may actually call.
     */
    private fun systemPromptFor(userText: String): String {
        val available = tools.available()
        val selected = RelevantToolSelector.select(available, userText)
        val built = if (selected.isEmpty()) {
            personaAndProtocol + "\n\nNessuno strumento è necessario per questa richiesta: lascia \"tool_calls\" vuoto."
        } else {
            val catalog = selected.joinToString("\n") { (name, description) -> "- $name: $description" }
            personaAndProtocol + "\n\nStrumenti disponibili (usa ESATTAMENTE questi nomi, mai altri):\n" + catalog
        }
        // § diagnostica non sensibile richiesta esplicitamente: solo dimensioni/conteggi,
        // mai il contenuto del prompt/dei nomi/descrizioni degli strumenti selezionati.
        Log.i(
            TAG,
            "conversational_prompt_built systemPromptChars=${built.length} " +
                "availableToolCount=${available.size} selectedToolCount=${selected.size}",
        )
        return built
    }

    private companion object {
        const val TAG = "JarvisBrain"

        val PROTOCOL_BLOCK = """
            Sei in MODALITÀ CONVERSAZIONALE. Rispondi SEMPRE e SOLO con un
            oggetto JSON, in questa forma esatta, senza testo prima o dopo:
            {"assistant_text": "...", "tool_calls": [], "memory_proposal": null, "follow_up_expected": false}

            - "assistant_text": quello che vuoi dire a Simone, in italiano naturale.
              Se non hai bisogno di uno strumento, usa solo questo campo.
            - "tool_calls": se un'operazione richiede uno strumento, aggiungi un
              oggetto {"id": "un id qualsiasi", "name": "nome_esatto_dello_strumento",
              "arguments": {...}}. Gli argomenti vanno presi SOLO da quello che
              Simone ha detto o dal contesto fornito, mai inventati. Usa solo nomi
              di strumenti presenti nell'elenco qui sotto.
            - Se il contesto indica un'operazione già in corso (es. un impegno
              appena creato) e Simone la corregge o la completa senza rinominarla
              di nuovo ("anzi, alle 18"), usa l'id indicato nel contesto invece di
              chiedere a quale impegno si riferisce.
            - Se ti mancano informazioni per procedere e non puoi ragionevolmente
              assumerle, lascia tool_calls vuoto, fai la domanda in assistant_text
              e imposta "follow_up_expected": true.
            - Non lasciare mai "assistant_text" vuoto se tool_calls è vuoto.
            - Ignora "memory_proposal" (lascialo null): per salvare qualcosa nella
              memoria personale usa uno strumento di memoria, non questo campo.
        """.trimIndent()
    }
}
