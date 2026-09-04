package com.simone.jarvismobile.engine

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.ai.AiRequest
import com.simone.jarvismobile.ai.AiRoutingContextProvider
import com.simone.jarvismobile.ai.RemoteAiEngine
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
import com.simone.jarvismobile.llm.DEFAULT_GENERATION_TIMEOUT_SECONDS
import com.simone.jarvismobile.llm.LlmRouter
import com.simone.jarvismobile.llm.ModelSlot
import com.simone.jarvismobile.tools.ToolRunner
import com.simone.jarvismobile.util.runCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Core the EXACT SAME [systemPrompt] (persona + protocol block + tool
 * catalog) the local model gets, and [parser] parses whichever one answered
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
        val prompt = if (contextBlock.isBlank()) userText else "$contextBlock\n\n$userText"
        val raw = tryRemoteReply(prompt, slot, timeoutSeconds)
            ?: router.chat(prompt, systemPrompt, slot, timeoutSeconds)
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
    private suspend fun tryRemoteReply(prompt: String, slot: ModelSlot, timeoutSeconds: Long): String? {
        val requestType = if (slot == ModelSlot.ADVANCED) AiRequestType.COMPLEX else AiRequestType.CHAT
        val decision = AiRoutingHeuristic.decide(requestType, routingContext.preferencesFor(requestType))
        if (decision.target == AiExecutionTarget.LOCAL) {
            Log.i(TAG, "CHAT -> LOCAL (reason=${decision.reason}, engine=conversazionale)")
            remoteChatState.setLastRoute("LOCAL (${decision.reason})")
            return null
        }

        val targetLabel = if (decision.target == AiExecutionTarget.REMOTE_BRAIN) "CORE BRAIN" else "CORE FAST"
        val requestId = java.util.UUID.randomUUID().toString()
        val request = AiRequest(
            requestId = requestId,
            text = prompt,
            systemPrompt = systemPrompt,
            requestType = requestType,
            timeoutSeconds = timeoutSeconds,
            preferredModel = if (decision.target == AiExecutionTarget.REMOTE_BRAIN) "brain" else null,
        )
        remoteChatState.activeRequestId = requestId
        val result = try {
            runCancellable { remoteAiEngine.generate(request) }.getOrNull()
        } finally {
            remoteChatState.activeRequestId = null
        }
        if (result == null || !result.success) {
            val reason = result?.failureReason ?: "cancelled_or_null"
            Log.i(TAG, "CORE FAILED -> LOCAL FALLBACK (reason=$reason, engine=conversazionale)")
            remoteChatState.setLastRoute("LOCAL (fallback dopo Core: $reason)")
            return null
        }
        val text = result.text?.takeIf { it.isNotBlank() }
        if (text == null) {
            Log.i(TAG, "CORE FAILED -> LOCAL FALLBACK (reason=empty_reply, engine=conversazionale)")
            remoteChatState.setLastRoute("LOCAL (fallback dopo Core: empty_reply)")
            return null
        }
        Log.i(TAG, "CHAT -> $targetLabel (engine=conversazionale)")
        remoteChatState.setLastRoute(targetLabel)
        return text
    }

    /** Post-hoc sentence-chunked delivery of an already-produced [response]. */
    fun replyEvents(response: AssistantResponse): List<BrainEvent> = SentenceStream.from(response)

    /**
     * Built once per process, like `ProModeCoordinator.systemPrompt`: the same
     * persona asset plus a protocol block and the live tool catalog, so a tool
     * registered in `ToolsModule` is automatically available here too — with
     * no separate prompt to keep in sync. Distinct instructions from Pro
     * mode's block: this brain is expected to hold multi-turn state and ask
     * clarifying questions ([AssistantResponse.followUpExpected]), which Pro
     * mode's single-shot protocol never uses.
     */
    private val systemPrompt: String by lazy {
        val persona = runCatching {
            context.assets.open("prompts/jarvis_system_it.md").bufferedReader().use { it.readText() }
        }.getOrDefault("Sei JARVIS, un assistente personale offline. Rispondi in italiano, breve e naturale.")
        val catalog = tools.available().joinToString("\n") { (name, description) -> "- $name: $description" }
        Log.i(TAG, "conversational_prompt_built tools=${tools.available().size}")
        buildString {
            append(persona.trim())
            append("\n\n")
            append(PROTOCOL_BLOCK)
            append("\n\nStrumenti disponibili (usa ESATTAMENTE questi nomi, mai altri):\n")
            append(catalog)
        }
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
