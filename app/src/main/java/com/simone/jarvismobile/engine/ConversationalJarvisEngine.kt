package com.simone.jarvismobile.engine

import android.util.Log
import com.simone.jarvismobile.core.engine.BrainReply
import com.simone.jarvismobile.core.engine.EngineTurnDiagnostics
import com.simone.jarvismobile.core.engine.JarvisEngineMode
import com.simone.jarvismobile.core.engine.ToolCallBudget
import com.simone.jarvismobile.core.intent.IntentAliases
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.DEFAULT_GENERATION_TIMEOUT_SECONDS
import com.simone.jarvismobile.tools.AgendaIntentRouter
import com.simone.jarvismobile.tools.AgendaRouting
import com.simone.jarvismobile.tools.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The LLM-first orchestrator (spec's central request). Owns exactly one
 * thing new — turning a transcript into a reply by reasoning first, tools
 * second — and delegates everything it reuses: [FastPathRouter] for
 * high-confidence commands, [AgendaIntentRouter] for the deterministic
 * planner CRUD Classic mode already has (delete/move/rename/complete/query,
 * fuzzy-resolved with disambiguation), [ContextAssembler] for what to tell
 * the model, [JarvisBrain] for the actual reasoning, [ToolRouter] for
 * execution, [ConversationManager] for cross-turn state.
 *
 * Deliberately holds NO reference to `ClassicJarvisEngine`/Modalità Pro:
 * that boundary is enforced one layer up, in [JarvisEngineRouter] (which
 * refuses to call this class at all when the local model isn't loaded,
 * falling back to Classic instead — see that class). Every failure this
 * class can hit on its own (timeout, malformed model output, the tool-call
 * cap) resolves to a safe canned message, never a crash and never silence —
 * except cancellation, which is deliberately NOT swallowed here (see
 * [handle]'s doc comment) so a user-initiated stop is reported as a stop,
 * not disguised as a completed answer.
 */
@Singleton
class ConversationalJarvisEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val fastPath: FastPathRouter,
    private val agendaIntents: AgendaIntentRouter,
    private val contextAssembler: ContextAssembler,
    private val brain: JarvisBrain,
    private val toolRouter: ToolRouter,
    private val conversationManager: ConversationManager,
) : JarvisEngine {

    /** A tool call this engine itself is waiting on the user to confirm/deny. */
    @Volatile private var pendingConfirmation: ToolCall? = null

    /** An [AgendaIntentRouter] "which one did you mean?" question awaiting a reply. */
    @Volatile private var pendingDisambiguation: PendingDisambiguation? = null

    private val _diagnostics = MutableStateFlow<List<EngineTurnDiagnostics>>(emptyList())
    val diagnostics: StateFlow<List<EngineTurnDiagnostics>> = _diagnostics.asStateFlow()

    /**
     * `CancellationException` is explicitly let through, never caught by the
     * broad crash guard below. Catching it there used to turn a user-initiated
     * stop into a normal-looking [CANNED_ERROR] answer — `AssistantTaskWorker`/
     * `SessionCoordinator.cancelTextGeneration` rely on this exception actually
     * propagating to tell a genuinely cancelled turn apart from one that
     * failed on its own (see `AssistantTaskWorker.doWork`'s own
     * `error is CancellationException` check, which this engine used to
     * silently defeat by swallowing the exception here first).
     */
    override suspend fun handle(transcript: String): String {
        val turn = TurnState(startedAt = System.currentTimeMillis(), cap = settings.jarvisToolLoopCap.first())

        val spoken = try {
            handlePendingConfirmation(transcript, turn)
                ?: handlePendingDisambiguation(transcript, turn)
                ?: runFastPath(transcript, turn)
                ?: runStructuredPath(transcript, turn)
                ?: runBrainLoop(transcript, turn)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "conversational_turn_crash ${t.javaClass.simpleName}")
            turn.fallbackOccurred = true
            CANNED_ERROR
        }

        recordDiagnostics(turn.toDiagnostics())
        return spoken
    }

    /** If this engine itself is waiting on a yes/no, resolve it before anything else. */
    private suspend fun handlePendingConfirmation(transcript: String, turn: TurnState): String? {
        val call = pendingConfirmation ?: return null
        pendingConfirmation = null
        if (IntentAliases.isCancellationOfPendingAction(transcript) || IntentAliases.isNegative(transcript)) {
            return "Va bene, annullato."
        }
        if (!IntentAliases.isAffirmative(transcript)) {
            // Neither yes nor no: treat this as a brand-new message instead.
            return null
        }
        return executeAndTrack(call, turn, confirmed = true)
    }

    /** If JARVIS just asked "which one did you mean?", this message answers it. */
    private suspend fun handlePendingDisambiguation(transcript: String, turn: TurnState): String? {
        val pending = pendingDisambiguation ?: return null
        pendingDisambiguation = null
        if (IntentAliases.isCancellationOfPendingAction(transcript) || IntentAliases.isNegative(transcript)) {
            return "Va bene, lascio stare."
        }
        return when (
            val resolved = agendaIntents.resolvePick(pending.args, pending.candidateIds, transcript)
        ) {
            is AgendaRouting.Call -> executeAndTrack(resolved.call, turn, confirmed = false)
            is AgendaRouting.Disambiguate -> {
                pendingDisambiguation = PendingDisambiguation(resolved.candidateIds, resolved.pending)
                resolved.question
            }
            is AgendaRouting.NotFound -> resolved.spoken
        }
    }

    private suspend fun runFastPath(transcript: String, turn: TurnState): String? {
        val match = fastPath.tryFastPath(transcript, conversationManager.snapshotText()) ?: return null
        turn.fastPathHit = true
        return executeAndTrack(match.call, turn, confirmed = false)
    }

    /**
     * Deterministic planner CRUD — delete/move/rename/complete/"when is X" —
     * fuzzy-resolved to a real entry id (or a disambiguation question) exactly
     * like Classic mode's own `AgendaIntentRouter` call, checked after
     * [FastPathRouter] for the same reason Classic checks `CommandMatcher`
     * first: an explicit command wins over a looser name match. The one
     * difference from Classic is [contextEntryId] — `SessionCoordinator`'s
     * `lastAgendaEntryId` is Classic-only state, so this uses
     * [ConversationManager]'s tracked pending task instead.
     *
     * This closes a real gap: without it, "segna le scadenze come completate"
     * or "quando devo andare dal dentista" have no resolved entry id to give
     * a tool that (by design, see `AgendaCrudTools`) only ever accepts one —
     * so they could only ever fail or hallucinate an id when left to
     * [runBrainLoop] alone.
     */
    private suspend fun runStructuredPath(transcript: String, turn: TurnState): String? {
        val routing = runCatching {
            agendaIntents.route(transcript, contextEntryId = conversationManager.current()?.entryId)
        }.getOrNull() ?: return null

        return when (routing) {
            is AgendaRouting.Call -> executeAndTrack(routing.call, turn, confirmed = false)
            is AgendaRouting.Disambiguate -> {
                pendingDisambiguation = PendingDisambiguation(routing.candidateIds, routing.pending)
                routing.question
            }
            is AgendaRouting.NotFound -> routing.spoken
        }
    }

    /** Runs [call] through [ToolRouter], tracks it for [ConversationManager], and turns the outcome into speech. */
    private suspend fun executeAndTrack(call: ToolCall, turn: TurnState, confirmed: Boolean): String {
        turn.toolsRequested += call.name
        val outcome = toolRouter.execute(call, turn.budget, online = false, confirmed = confirmed)
        conversationManager.onToolExecuted(call, outcome)
        return when (outcome) {
            is ToolOutcome.Done -> { turn.toolsExecuted += call.name; outcome.spoken }
            is ToolOutcome.Failed -> outcome.spoken
            is ToolOutcome.NeedsConfirmation -> {
                pendingConfirmation = outcome.call
                outcome.prompt
            }
        }
    }

    /**
     * The real reasoning loop: `JarvisBrain` proposes a response; any tool
     * calls it asks for run through [ToolRouter] (bounded by the turn's
     * [ToolCallBudget]); the results are fed back for one more round so the
     * model can compose a natural final sentence, exactly like
     * `ProModeCoordinator.composeFinalReply` but allowed to repeat — up to
     * [MAX_BRAIN_ROUNDS] — for a genuine multi-step chain.
     *
     * Only the FIRST round gets the full [DEFAULT_GENERATION_TIMEOUT_SECONDS]
     * (real reasoning); follow-up rounds only need to phrase an already-known
     * tool result, so they get [FOLLOWUP_TIMEOUT_SECONDS] instead — a stuck
     * or slow follow-up no longer costs a full 90s on top of round 1's own
     * 90s, which is what used to make a bad multi-round turn take minutes.
     */
    private suspend fun runBrainLoop(transcript: String, turn: TurnState): String {
        val reasoningMode = settings.jarvisReasoningMode.first()
        val slot = brain.resolveSlot(reasoningMode, transcript)
        val assembled = contextAssembler.assemble(transcript, conversationManager.snapshotText())
        turn.memoriesRetrieved = assembled.memoriesRetrieved
        var contextBlock = assembled.text
        var currentText = transcript
        var rounds = 0

        while (true) {
            rounds++
            if (rounds > MAX_BRAIN_ROUNDS) return CANNED_ERROR
            val timeoutSeconds = if (rounds == 1) DEFAULT_GENERATION_TIMEOUT_SECONDS else FOLLOWUP_TIMEOUT_SECONDS

            val reply = brain.reply(currentText, contextBlock, slot, timeoutSeconds)
            if (reply is BrainReply.Unavailable) {
                // JarvisEngineRouter is expected to have already kept us from being
                // called at all in this case; this is the honest fallback if the
                // model unloads mid-turn regardless.
                turn.fallbackOccurred = true
                return CANNED_ERROR
            }
            val ready = reply as BrainReply.Ready
            if (!ready.parsedCleanly) turn.parseError = true
            if (turn.firstEmitAt == null) turn.firstEmitAt = System.currentTimeMillis()

            val response = ready.response
            if (response.toolCalls.isEmpty()) {
                return response.assistantText.trim().ifBlank { "Fatto." }
            }

            val toolResults = StringBuilder()
            var confirmationPrompt: String? = null
            for (call in response.toolCalls) {
                turn.toolsRequested += call.name
                if (turn.budget.exhausted) {
                    toolResults.append("Ho eseguito il numero massimo di operazioni per questo turno.\n")
                    break
                }
                when (val outcome = toolRouter.execute(call, turn.budget, online = false, confirmed = false)) {
                    is ToolOutcome.Done -> {
                        turn.toolsExecuted += call.name
                        conversationManager.onToolExecuted(call, outcome)
                        toolResults.append(outcome.spoken).append('\n')
                    }
                    is ToolOutcome.Failed -> toolResults.append(outcome.spoken).append('\n')
                    is ToolOutcome.NeedsConfirmation -> {
                        pendingConfirmation = outcome.call
                        confirmationPrompt = outcome.prompt
                    }
                }
                if (confirmationPrompt != null) break
            }
            confirmationPrompt?.let { return it }

            // One more round so the model can turn raw tool output into a
            // natural sentence instead of the caller composing it by hand.
            currentText = "Risultato degli strumenti eseguiti:\n${toolResults.toString().trim()}\n\n" +
                "Se serve un altro strumento richiedilo in tool_calls, altrimenti componi ora la risposta " +
                "finale per Simone in assistant_text, in linguaggio naturale, e lascia tool_calls vuoto."
            contextBlock = ""
        }
    }

    private fun recordDiagnostics(entry: EngineTurnDiagnostics) {
        _diagnostics.value = (_diagnostics.value + entry).takeLast(MAX_DIAGNOSTICS_HISTORY)
    }

    /** Mirrors `SessionCoordinator.PendingPick` — kept local since this engine's pending state is its own. */
    private data class PendingDisambiguation(val candidateIds: List<String>, val args: Map<String, String>)

    /** Per-turn mutable bookkeeping, collapsed into [EngineTurnDiagnostics] at the end. */
    private class TurnState(val startedAt: Long, cap: Int) {
        val budget = ToolCallBudget(cap)
        val toolsRequested = ArrayList<String>()
        val toolsExecuted = ArrayList<String>()
        var fastPathHit = false
        var fallbackOccurred = false
        var parseError = false
        var firstEmitAt: Long? = null
        var memoriesRetrieved = 0

        /** Never includes the reply text itself — only counts/booleans, per [EngineTurnDiagnostics]'s contract. */
        fun toDiagnostics(): EngineTurnDiagnostics {
            val now = System.currentTimeMillis()
            return EngineTurnDiagnostics(
                engine = JarvisEngineMode.CONVERSAZIONALE,
                fastPathHit = fastPathHit,
                timeToFirstEmitMs = (firstEmitAt ?: now) - startedAt,
                totalTurnMs = now - startedAt,
                memoriesRetrieved = memoriesRetrieved,
                toolsRequested = toolsRequested,
                toolsExecuted = toolsExecuted,
                fallbackOccurred = fallbackOccurred,
                parseError = parseError,
                timestamp = startedAt,
            )
        }
    }

    private companion object {
        const val TAG = "JarvisConversational"

        // Bounded so a flaky multi-round turn cannot silently run for minutes:
        // worst case is now DEFAULT_GENERATION_TIMEOUT_SECONDS (round 1) plus
        // (MAX_BRAIN_ROUNDS - 1) * FOLLOWUP_TIMEOUT_SECONDS, ~150s instead of
        // the previous up-to-6-rounds-at-90s-each (~9 minutes).
        const val MAX_BRAIN_ROUNDS = 3
        const val FOLLOWUP_TIMEOUT_SECONDS = 30L

        const val MAX_DIAGNOSTICS_HISTORY = 20
        const val CANNED_ERROR =
            "Non sono riuscito a completare la richiesta in modalità conversazionale. Riprova, per favore."
    }
}
