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
import com.simone.jarvismobile.core.engine.ParseOutcome
import com.simone.jarvismobile.core.engine.PromptDiagnostics
import com.simone.jarvismobile.core.engine.ReasoningMode
import com.simone.jarvismobile.core.engine.SentenceStream
import com.simone.jarvismobile.core.engine.SystemPromptComposer
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
 * [SystemPromptComposer.RICH_PROTOCOL_BLOCK] or the live tool catalog, so [ResponseParser] would treat
 * every Core reply as [ParseResult.PlainText] and tool-calling would go
 * silently dark for any turn Core happened to answer. That gap is now
 * closed: `jarvis-protocol/main` v1.1.0 added `JarvisRequest.systemPrompt`
 * (optional, additive — see that repo's CHANGELOG), `jarvis-core`'s
 * `RequestOrchestrator` now forwards it to the resolved provider instead of
 * its own fixed default, and [RemoteAiEngine] threads
 * [AiRequest.systemPrompt] through to it — so [tryRemoteReply] can hand
 * Core the EXACT SAME [systemPromptFor] result (§ FASE 2A.3: a compact
 * [SystemPromptComposer.Tier.FAST] prompt for a FAST-slot turn, the full
 * [SystemPromptComposer.Tier.RICH] one otherwise — see that class' own doc
 * comment) the local model gets, and [parser] parses whichever one answered
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
 * against**: whether Core's chosen model actually FOLLOWS the active
 * protocol block's strict-JSON instruction is a property of that model, not of this wiring —
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

    /** Set by the last [systemPromptFor] call — § FASE 2A.5 diagnostica richiesta esplicitamente, see [PromptDiagnostics]. */
    @Volatile var lastPromptDiagnostics: PromptDiagnostics? = null
        private set

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
        // § FASE 2A.3 bugfix found during this phase's own audit: inside a
        // multi-round tool loop, `ConversationalJarvisEngine.runBrainLoop`
        // calls `reply()` again with `userText` replaced by a synthetic
        // "Risultato degli strumenti eseguiti: ..." follow-up — that text
        // has no tool-shaped keywords of its own, so selecting tools from IT
        // (as FASE 2A.2 did, unconditionally) would silently starve round 2+
        // of every tool the ORIGINAL request might still need, even though
        // the follow-up text itself invites the model to request another one
        // ("Se serve un altro strumento richiedilo in tool_calls"). Tool
        // relevance is a property of what Simone actually asked, not of this
        // loop's internal continuation text — [toolSelectionText] defaults to
        // [userText] for a normal single-shot turn, and the caller passes the
        // turn's original transcript explicitly across every round instead.
        toolSelectionText: String = userText,
    ): BrainReply {
        // § logging temporaneo obbligatorio, audit "Conversational mode non
        // tenta più Core dopo integrazione" — prova che reply() sia stato
        // raggiunto davvero per questo turno, prima di qualunque altra cosa.
        Log.i(TAG, "BRAIN_REPLY_ENTER slot=$slot")
        val prompt = if (contextBlock.isBlank()) userText else "$contextBlock\n\n$userText"
        val tier = if (slot == ModelSlot.ADVANCED) SystemPromptComposer.Tier.RICH else SystemPromptComposer.Tier.FAST
        val turnSystemPrompt = systemPromptFor(toolSelectionText, tier)
        val remote = tryRemoteReply(prompt, turnSystemPrompt, slot, timeoutSeconds)
        if (remote == null) Log.i(TAG, "BRAIN_FALLBACK_LOCAL")
        val raw = remote
            ?: run {
                // § FASE 2A.4 root cause of "Accendi la luce della camera" ->
                // "'TEST CORE' è stato eseguito correttamente": `LitertLmEngine.chat()`
                // deliberately reuses one native `Conversation` (KV cache) across
                // calls to the same slot and, by design (see its own doc comment),
                // does NOT re-seed it when the incoming systemPrompt differs from
                // the one it was seeded with — a fix for Classic mode's own turns,
                // where re-seeding on every per-turn-varying system prompt was
                // destroying ITS chat memory. FASE 2A.2/2A.3 made this brain's own
                // systemPrompt vary turn-to-turn too (selected tools, FAST/RICH
                // tier) — so a local-fallback turn silently inherited a PRIOR,
                // unrelated turn's live conversation: the model never even saw
                // this turn's real system prompt (tool catalog included), and its
                // native history still held the old exchange, verbatim explaining
                // the observed contamination. This brain was never designed to
                // rely on that native memory in the first place — every piece of
                // cross-turn continuity it wants is already explicit
                // (`ConversationManager`/`ContextAssembler`), never implicit model
                // memory — so forcing a fresh, correctly-seeded conversation here
                // costs nothing intentional. Classic mode's own `SessionCoordinator`
                // calls into the very same shared `LlmRouter`/`LitertLmEngine`
                // instances and is deliberately left untouched (no reset added
                // there): this fix is scoped to the one call site that was
                // actually measured to cause the bug.
                router.resetConversation(slot)
                router.chat(prompt, turnSystemPrompt, slot, timeoutSeconds)
            }
            ?: return BrainReply.Unavailable
        return when (val parsed = parser.parse(raw)) {
            is ParseResult.Valid -> BrainReply.Ready(
                parsed.response,
                parsedCleanly = true,
                parseOutcome = ParseOutcome.VALID,
            )
            is ParseResult.Repaired -> BrainReply.Ready(
                parsed.response,
                parsedCleanly = true,
                parseOutcome = ParseOutcome.REPAIRED,
            )
            // Never treated as tool calls — ResponseParser's contract already
            // guarantees invalid/unrepairable JSON never reaches a caller as
            // anything but plain text. § FASE 2A.5-bis: `looksLikeAttemptedJson`
            // tells apart a genuine parse failure (MALFORMED_JSON, worth
            // flagging) from the model correctly answering in plain text
            // because no tool was needed (PLAIN_TEXT, not an error at all).
            is ParseResult.PlainText -> BrainReply.Ready(
                AssistantResponse(assistantText = parsed.rawText.trim()),
                parsedCleanly = false,
                parseOutcome = ParseOutcome.fromParseResult(parsed),
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
     * The rich (BRAIN/local-tier) persona text, loaded once per process from
     * the shared asset — unmodified, trimmed. [systemPromptFor] hands this to
     * [SystemPromptComposer] only for [SystemPromptComposer.Tier.RICH]; the
     * [SystemPromptComposer.Tier.FAST] tier uses its own compact, built-in
     * persona instead (§ FASE 2A.3 — see that class' doc comment for why).
     */
    private val richPersona: String by lazy {
        runCatching {
            context.assets.open("prompts/jarvis_system_it.md").bufferedReader().use { it.readText() }
        }.getOrDefault("Sei JARVIS, un assistente personale offline. Rispondi in italiano, breve e naturale.")
    }

    /**
     * § FASE 2A.2/2A.3 — root cause of the ~72s/~26s FAST latencies measured
     * in FASE 2A.1: this used to embed all ~53 registered tools AND the full
     * ~4600-char persona+protocol unconditionally on every turn, regardless
     * of target. [RelevantToolSelector] (FASE 2A.2) decides — deterministically,
     * no second LLM — which of [ToolRunner.available]'s live tools are
     * plausibly relevant to [toolSelectionText]; [SystemPromptComposer]
     * (FASE 2A.3) then builds either the compact FAST-tier prompt (measured:
     * ~665 chars for the common no-tool case, vs. the ~4650 FASE 2A.2 already
     * sent for the same turn) or the unconstrained rich tier, per [tier].
     * [ToolRegistry] itself, tool execution and argument validation are
     * entirely untouched — this only decides what the model is TOLD about,
     * never what it may actually call.
     */
    private fun systemPromptFor(toolSelectionText: String, tier: SystemPromptComposer.Tier): String {
        val available = tools.available()
        val selected = RelevantToolSelector.select(available, toolSelectionText)
        val built = SystemPromptComposer.compose(tier, richPersona, selected)
        val families = RelevantToolSelector.familiesOf(selected).map { it.name }
        // § FASE 2A.5 diagnostica richiesta esplicitamente: tool family
        // selezionata, tool disponibili al modello, tier — mai contenuto del
        // prompt o argomenti. Set by the last systemPromptFor() call, mirroring
        // the existing `lastNeedsReasoning`/`lastLoadDetail` "set by the last
        // call" convention already used elsewhere in this codebase (e.g.
        // LlmIntentClassifier), so ConversationalJarvisEngine can read it
        // right after each reply() without a second parallel return channel.
        lastPromptDiagnostics = PromptDiagnostics(
            tier = tier,
            availableToolCount = available.size,
            selectedToolCount = selected.size,
            toolFamilies = families,
            systemPromptChars = built.length,
        )
        // § diagnostica non sensibile richiesta esplicitamente: solo dimensioni/conteggi,
        // mai il contenuto del prompt/dei nomi/descrizioni degli strumenti selezionati.
        Log.i(
            TAG,
            "conversational_prompt_built tier=$tier systemPromptChars=${built.length} " +
                "availableToolCount=${available.size} selectedToolCount=${selected.size} " +
                "toolFamilies=$families",
        )
        return built
    }

    private companion object {
        const val TAG = "JarvisBrain"
    }
}
