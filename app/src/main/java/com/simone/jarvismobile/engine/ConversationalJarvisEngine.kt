package com.simone.jarvismobile.engine

import android.util.Log
import com.simone.jarvismobile.ai.RemoteChatState
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.agenda.ItalianDateTimeParser
import com.simone.jarvismobile.core.engine.BrainReply
import com.simone.jarvismobile.core.engine.EngineTurnDiagnostics
import com.simone.jarvismobile.core.engine.GroundingGate
import com.simone.jarvismobile.core.engine.JarvisEngineMode
import com.simone.jarvismobile.core.engine.ParseOutcome
import com.simone.jarvismobile.core.engine.ToolCallBudget
import com.simone.jarvismobile.core.health.HealthAggregation
import com.simone.jarvismobile.core.health.HealthMetric
import com.simone.jarvismobile.core.health.HealthQueryParser
import com.simone.jarvismobile.core.health.HealthRange
import com.simone.jarvismobile.core.intent.IntentAliases
import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.core.tools.DeviceInfoFollowUp
import com.simone.jarvismobile.core.tools.GROUNDED_FAMILIES
import com.simone.jarvismobile.core.tools.HomeControlDetector
import com.simone.jarvismobile.core.tools.RelevantToolSelector
import com.simone.jarvismobile.core.tools.ToolFamily
import com.simone.jarvismobile.core.weather.WeatherDaysAhead
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.DEFAULT_GENERATION_TIMEOUT_SECONDS
import com.simone.jarvismobile.tools.AgendaIntentRouter
import com.simone.jarvismobile.util.runCancellable
import com.simone.jarvismobile.tools.AgendaRouting
import com.simone.jarvismobile.tools.CommandMatcher
import com.simone.jarvismobile.tools.Match
import com.simone.jarvismobile.tools.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
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
    private val remoteChatState: RemoteChatState,
    private val contextEngine: ContextEngine,
) : JarvisEngine {

    /**
     * § FASE 2A.5-bis root cause (audit "AUDIT TOOL DISPONIBILI" — why a
     * network-requiring tool like `get_weather` could never succeed even
     * with real connectivity): every call site in this engine used to pass
     * the `online` parameter's default, `false`, to `ToolRouter.execute`/
     * `ToolRunner.run` — a network-requiring [com.simone.jarvismobile.core.tools.Tool]
     * (`ToolRegistry.resolve`) is rejected outright whenever `online` is
     * false, regardless of whether the device is actually connected. No
     * tool in the registry needed network before this phase (an offline-
     * first, deliberate choice — see `CLAUDE.md`), so the bug was latent:
     * adding a real weather tool would have made it always fail, online or
     * not. [ContextEngine.state]'s `networkAvailable` is the same live
     * signal `WeatherManager`/other providers already read (§ one-shot,
     * already-existing state, no new polling) — `== true` so an unknown
     * network state (`null`) is treated as offline, not silently assumed
     * connected.
     */
    private fun isOnline(): Boolean = contextEngine.state.value.networkAvailable == true

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
        // § FASE 2A.6 §10 — one snapshot for the whole turn, not re-read per
        // branch: what a network-requiring tool would actually be gated on
        // right now, regardless of whether one is attempted this turn.
        turn.networkAvailable = isOnline()

        val spoken = try {
            handlePendingConfirmation(transcript, turn)
                ?: handlePendingDisambiguation(transcript, turn)
                ?: runFastPath(transcript, turn)
                ?: runStructuredPath(transcript, turn)
                ?: runHomeControlGuard(transcript, turn)
                ?: runCapabilityFastPath(transcript, turn)
                ?: runFollowUpFastPath(transcript, turn)
                ?: runBrainLoop(transcript, turn)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "conversational_turn_crash ${t.javaClass.simpleName}")
            turn.fallbackOccurred = true
            turn.routingPath = "ERROR"
            CANNED_ERROR
        }

        recordDiagnostics(turn.toDiagnostics())
        return spoken
    }

    /** If this engine itself is waiting on a yes/no, resolve it before anything else. */
    private suspend fun handlePendingConfirmation(transcript: String, turn: TurnState): String? {
        val call = pendingConfirmation ?: return null
        // § logging temporaneo obbligatorio, audit "Conversational mode non
        // tenta più Core" — se questo compare per un messaggio che non è una
        // risposta sì/no reale (es. "Ciao"), un turno precedente ha lasciato
        // una conferma sospesa che intercetta ogni messaggio successivo
        // PRIMA che raggiunga fast-path/structured-path/runBrainLoop.
        Log.i(TAG, "ENGINE_BRANCH=pending_confirmation call=${call.name}")
        turn.routingPath = "PENDING_CONFIRMATION"
        // Visibile anche in Diagnostica › "Ultima risposta chat" (§ stesso
        // pattern già in uso in JarvisBrain), non solo in Logcat — l'utente
        // può verificare senza adb se un turno è stato intercettato qui
        // invece di raggiungere mai runBrainLoop/tryRemoteReply.
        remoteChatState.setLastRoute("LOCAL (bypass: conferma sospesa)")
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
        // § logging temporaneo obbligatorio, audit "Conversational mode non
        // tenta più Core" — stesso principio di ENGINE_BRANCH=pending_confirmation
        // sopra: se compare per un messaggio come "Ciao" (non una risposta
        // reale alla disambiguazione), questo turno non raggiunge mai
        // fast-path/structured-path/runBrainLoop/JarvisBrain.tryRemoteReply.
        Log.i(TAG, "ENGINE_BRANCH=pending_disambiguation candidates=${pending.candidateIds.size}")
        turn.routingPath = "PENDING_DISAMBIGUATION"
        remoteChatState.setLastRoute("LOCAL (bypass: disambiguazione sospesa)")
        pendingDisambiguation = null
        if (IntentAliases.isCancellationOfPendingAction(transcript) || IntentAliases.isNegative(transcript)) {
            return "Va bene, lascio stare."
        }
        return when (
            val resolved = agendaIntents.resolvePick(pending.args, pending.candidateIds, transcript)
        ) {
            is AgendaRouting.Call -> executeAndTrack(resolved.call, turn, confirmed = false)
            is AgendaRouting.Disambiguate -> {
                // Il caso "trappola": non riconosciuto come risposta valida,
                // quindi si ri-arma per il turno SUCCESSIVO — se il prossimo
                // messaggio dell'utente è una domanda qualunque non correlata,
                // verrà intercettato di nuovo qui, non da runBrainLoop.
                Log.i(TAG, "ENGINE_DISAMBIGUATION_STUCK candidates=${resolved.candidateIds.size}")
                pendingDisambiguation = PendingDisambiguation(resolved.candidateIds, resolved.pending)
                resolved.question
            }
            is AgendaRouting.NotFound -> resolved.spoken
        }
    }

    private suspend fun runFastPath(transcript: String, turn: TurnState): String? {
        val match = fastPath.tryFastPath(transcript, conversationManager.snapshotText()) ?: return null
        Log.i(TAG, "ENGINE_BRANCH=fast_path tool=${match.call.name}")
        remoteChatState.setLastRoute("LOCAL (bypass: comando deterministico)")
        turn.fastPathHit = true
        turn.routingPath = "FAST_PATH"
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
     *
     * Only [AgendaRouting.Call] and [AgendaRouting.Disambiguate] answer here
     * directly — both are a genuine, successful structured-path result.
     * [AgendaRouting.NotFound] deliberately does NOT: the lexical matcher
     * behind it (`TextNormalizer.matches`) requires every significant word of
     * the phrase to appear in the saved title, so a real entry can exist under
     * slightly different wording and still miss ("sposta dal dentista
     * venerdì" against a wrong-day guess, or a title worded differently than
     * the request). Unlike Classic — which stops here by design, because it
     * has no other way to look — the conversational engine still has
     * `runBrainLoop` next, and the model's tool catalog includes the
     * read-only `list_agenda`: it can look at the real titles itself and
     * match by meaning where the literal matcher couldn't, then act on the
     * id it actually saw. That is strictly safer than it sounds — every
     * write tool here still goes through the same `ToolRouter`/confirmation
     * policy regardless of which path proposed it (a wrong `move_agenda`/
     * `complete_agenda` guess just fails with "not found"; a wrong
     * `delete_agenda` guess still stops at "confirm deleting X?").
     */
    private suspend fun runStructuredPath(transcript: String, turn: TurnState): String? {
        val routing = runCancellable {
            agendaIntents.route(transcript, contextEntryId = conversationManager.current()?.entryId)
        }.getOrNull() ?: return null

        return when (routing) {
            is AgendaRouting.Call -> {
                Log.i(TAG, "ENGINE_BRANCH=structured_path tool=${routing.call.name}")
                remoteChatState.setLastRoute("LOCAL (bypass: comando agenda)")
                turn.routingPath = "STRUCTURED_AGENDA"
                executeAndTrack(routing.call, turn, confirmed = false)
            }
            is AgendaRouting.Disambiguate -> {
                Log.i(TAG, "ENGINE_BRANCH=structured_path (disambiguate, candidates=${routing.candidateIds.size})")
                remoteChatState.setLastRoute("LOCAL (bypass: disambiguazione agenda)")
                turn.routingPath = "STRUCTURED_AGENDA"
                pendingDisambiguation = PendingDisambiguation(routing.candidateIds, routing.pending)
                routing.question
            }
            is AgendaRouting.NotFound -> {
                turn.structuredMissHint = routing.spoken
                null
            }
        }
    }

    /**
     * § FASE 2A.6 §2/§9 — a request for real home-automation control (room
     * lighting, climate, shutters, locks) that this app has no smart-home
     * integration for at all (Phase 7, "Not started" — see `CLAUDE.md`).
     * Checked as its own deterministic guard, ahead of both the capability
     * router below and the model: the risk this closes is specific —
     * `RelevantToolSelector`'s DEVICE family (the phone's own `flashlight`)
     * must never silently stand in for a room-lighting request just because
     * it happens to be the only "turn something on/off" tool registered.
     * Never a side effect, never a guess — an honest, deterministic
     * "unsupported" answer, exactly what §9's test 6 requires.
     */
    private fun runHomeControlGuard(transcript: String, turn: TurnState): String? {
        if (!HomeControlDetector.looksLikeUnsupportedHomeControl(transcript)) return null
        Log.i(TAG, "ENGINE_BRANCH=home_control_unsupported")
        remoteChatState.setLastRoute("LOCAL (bypass: controllo domotico non supportato)")
        turn.routingPath = "HOME_CONTROL_UNSUPPORTED"
        return "Non ho ancora un'integrazione per il controllo della casa (luci, clima, tapparelle): " +
            "posso solo controllare la torcia del telefono."
    }

    /**
     * § FASE 2A.6 §2 — capability-first routing: when [transcript] matches
     * EXACTLY ONE specific [ToolFamily] via `RelevantToolSelector.matchedFamilies`
     * (never the conservative "ambiguous → full catalog" case, which is
     * genuinely ambiguous and must still go to the model), and that family
     * has a known, single, high-confidence tool, this calls it directly —
     * no LLM round at all. This is the same architectural idea `runFastPath`/
     * `runStructuredPath` already prove works for agenda commands, extended
     * to the two capabilities added in FASE 2A.5-bis (`get_weather`,
     * `get_health_summary`) that previously had no deterministic path and
     * depended entirely on the model choosing to call them. Two or more
     * families matching (e.g. "come ho dormito e gli impegni di domani")
     * falls through to `runBrainLoop`, where grounding enforcement is the
     * safety net for a genuinely multi-source request.
     */
    private suspend fun runCapabilityFastPath(transcript: String, turn: TurnState): String? {
        val matched = RelevantToolSelector.matchedFamilies(transcript)
        when (matched.singleOrNull()) {
            ToolFamily.WEATHER -> {
                when (val plan = weatherCall(transcript)) {
                    is WeatherCapabilityPlan.OutOfRange -> {
                        // § FASE 2A.7 RELEASE GATE 3 — resolved deterministically,
                        // honestly, WITHOUT ever calling `get_weather` with a
                        // silently-clamped day count: this app genuinely cannot
                        // forecast that far, so say so directly instead of
                        // spending an LLM round on a request the tool would
                        // reject anyway.
                        Log.i(TAG, "ENGINE_BRANCH=capability_fast_path weather_out_of_range")
                        remoteChatState.setLastRoute("LOCAL (bypass: previsione fuori intervallo supportato)")
                        turn.routingPath = "CAPABILITY_FAST_PATH"
                        turn.modelRounds = 0
                        return WEATHER_OUT_OF_RANGE_MESSAGE
                    }
                    is WeatherCapabilityPlan.Call -> {
                        Log.i(TAG, "ENGINE_BRANCH=capability_fast_path tool=${plan.call.name}")
                        remoteChatState.setLastRoute("LOCAL (bypass: capability diretta)")
                        turn.routingPath = "CAPABILITY_FAST_PATH"
                        turn.modelRounds = 0
                        return executeAndTrack(plan.call, turn, confirmed = false)
                    }
                }
            }
            ToolFamily.HEALTH -> {
                val call = healthCall(transcript)
                Log.i(TAG, "ENGINE_BRANCH=capability_fast_path tool=${call.name}")
                remoteChatState.setLastRoute("LOCAL (bypass: capability diretta)")
                turn.routingPath = "CAPABILITY_FAST_PATH"
                turn.modelRounds = 0
                return executeAndTrack(call, turn, confirmed = false)
            }
            else -> return null
        }
    }

    /** What [weatherCall] decided for one request — either a real `get_weather` call, or an honest "out of range" that never reaches the tool at all. */
    private sealed interface WeatherCapabilityPlan {
        data class Call(val call: ToolCall) : WeatherCapabilityPlan
        data class OutOfRange(val requestedDaysAhead: Int) : WeatherCapabilityPlan
    }

    /**
     * [ItalianDateTimeParser] resolves "domani"/"dopodomani"/an explicit date;
     * anything else (or none named) defaults to today. § FASE 2A.7 RELEASE
     * GATE 3 real bug fix: the real (unclamped) day offset is now checked
     * against [WeatherDaysAhead] BEFORE building any tool call — "tra 10
     * giorni" used to be silently coerced into "tra 3 giorni" here, with
     * neither the model nor the user ever told the difference.
     */
    private fun weatherCall(transcript: String): WeatherCapabilityPlan {
        val now = LocalDateTime.now()
        val parsed = ItalianDateTimeParser.parse(transcript, now)
        val requestedDaysAhead = if (parsed.dateExplicit && parsed.date != null) {
            ChronoUnit.DAYS.between(now.toLocalDate(), parsed.date).toInt()
        } else {
            0
        }
        return when (val resolution = WeatherDaysAhead.resolve(requestedDaysAhead)) {
            is WeatherDaysAhead.Resolution.OutOfRange -> WeatherCapabilityPlan.OutOfRange(resolution.requestedDaysAhead)
            is WeatherDaysAhead.Resolution.Supported -> WeatherCapabilityPlan.Call(
                ToolCall(
                    id = UUID.randomUUID().toString(),
                    name = "get_weather",
                    arguments = JsonObject(mapOf("days_ahead" to JsonPrimitive(resolution.daysAhead.toString()))),
                    requiresConfirmation = false,
                ),
            )
        }
    }

    /**
     * § FASE 2A.8 RELEASE GATE D real bug fix: [transcript] is parsed by
     * [HealthQueryParser] into metric+range+aggregation, so "stanotte"/"il 2
     * settembre" build a specific-night call, "questa settimana" (no
     * "media") builds a TOTAL, and "media del sonno questa settimana" builds
     * an AVERAGE — three genuinely different answers instead of the single
     * weekly-average every health question used to get regardless of
     * phrasing (§ FASE 2A.7's `period` argument only got as far as
     * last_night-vs-week).
     */
    private fun healthCall(transcript: String): ToolCall {
        val spec = HealthQueryParser.parse(transcript, LocalDateTime.now())
        val args = buildMap {
            put("metric", JsonPrimitive(if (spec.metric == HealthMetric.RESTING_HEART_RATE) "resting_heart_rate" else "sleep_duration"))
            put("aggregation", JsonPrimitive(if (spec.aggregation == HealthAggregation.AVERAGE) "average" else "total"))
            when (val range = spec.range) {
                is HealthRange.Night -> put("range", JsonPrimitive(range.date.toString()))
                HealthRange.Week -> put("range", JsonPrimitive("week"))
            }
        }
        return ToolCall(
            id = UUID.randomUUID().toString(),
            name = "get_health_summary",
            arguments = JsonObject(args),
            requiresConfirmation = false,
        )
    }

    /**
     * § FASE 2A.8 RELEASE GATE A/C — resolves a bare elliptical follow-up
     * ("E dopodomani?", "Quanta ne ho nel telefono?") against the LAST
     * capability/knowledge topic this conversation touched, instead of
     * letting it reach the model with no family keyword of its own to go
     * on (see [ConversationManager]'s own doc comment for the audited root
     * cause). Checked after [runCapabilityFastPath] — an explicit,
     * keyword-bearing capability request always wins — and before
     * [runBrainLoop]. Deliberately narrow: it only fires when [transcript]
     * carries NO family keyword of its own
     * (`RelevantToolSelector.matchedFamilies` empty), so it can never
     * shadow a genuine new capability request.
     *
     * Two independent resolutions, checked in order:
     *  1. A remembered [ConversationManager.currentKnowledgeTopic] (e.g.
     *     "ram", noted after a RAM/VRAM knowledge exchange) plus a bare
     *     partitive shape ("Quanta ne ho?") resolves via
     *     [DeviceInfoFollowUp] — never for "vram" (no reliable Android
     *     value), which falls through instead of guessing.
     *  2. A remembered [ConversationManager.currentCapabilityTopic]
     *     (WEATHER/AGENDA/HEALTH) plus a genuinely date-shaped
     *     continuation is resolved by calling the SAME capability builders
     *     [runCapabilityFastPath] itself uses ([weatherCall],
     *     [CommandMatcher.agendaCall], [healthCall]) directly on the bare
     *     text — no new date parser: none of the three check for family
     *     keywords themselves, only date/period words, so they correctly
     *     extract "dopodomani" on their own.
     */
    private suspend fun runFollowUpFastPath(transcript: String, turn: TurnState): String? {
        if (RelevantToolSelector.matchedFamilies(transcript).isNotEmpty()) return null

        val knowledgeTopic = conversationManager.currentKnowledgeTopic()
        if (knowledgeTopic != null && DeviceInfoFollowUp.looksLikePartitiveFollowUp(transcript)) {
            val metric = DeviceInfoFollowUp.resolveDeviceInfoMetric(knowledgeTopic)
            if (metric != null) {
                val call = ToolCall(
                    id = UUID.randomUUID().toString(),
                    name = "get_device_info",
                    arguments = JsonObject(mapOf("metric" to JsonPrimitive(metric))),
                    requiresConfirmation = false,
                )
                Log.i(TAG, "ENGINE_BRANCH=follow_up_fast_path tool=${call.name}")
                remoteChatState.setLastRoute("LOCAL (bypass: follow-up device info)")
                turn.routingPath = "CAPABILITY_FAST_PATH"
                turn.modelRounds = 0
                return executeAndTrack(call, turn, confirmed = false)
            }
            // Topic remembered but not answerable (e.g. "vram") — fall
            // through rather than silently substituting a different metric.
        }

        val capabilityTopic = conversationManager.currentCapabilityTopic() ?: return null
        val now = LocalDateTime.now()
        if (!ItalianDateTimeParser.parse(transcript, now).dateExplicit) return null

        return when (capabilityTopic) {
            ToolFamily.WEATHER -> when (val plan = weatherCall(transcript)) {
                is WeatherCapabilityPlan.OutOfRange -> {
                    Log.i(TAG, "ENGINE_BRANCH=follow_up_fast_path weather_out_of_range")
                    remoteChatState.setLastRoute("LOCAL (bypass: previsione fuori intervallo supportato)")
                    turn.routingPath = "CAPABILITY_FAST_PATH"
                    turn.modelRounds = 0
                    WEATHER_OUT_OF_RANGE_MESSAGE
                }
                is WeatherCapabilityPlan.Call -> {
                    Log.i(TAG, "ENGINE_BRANCH=follow_up_fast_path tool=${plan.call.name}")
                    remoteChatState.setLastRoute("LOCAL (bypass: follow-up capability)")
                    turn.routingPath = "CAPABILITY_FAST_PATH"
                    turn.modelRounds = 0
                    executeAndTrack(plan.call, turn, confirmed = false)
                }
            }
            ToolFamily.AGENDA -> {
                val call = (CommandMatcher.agendaCall(transcript, now) as? Match.Run)?.call ?: return null
                Log.i(TAG, "ENGINE_BRANCH=follow_up_fast_path tool=${call.name}")
                remoteChatState.setLastRoute("LOCAL (bypass: follow-up capability)")
                turn.routingPath = "CAPABILITY_FAST_PATH"
                turn.modelRounds = 0
                executeAndTrack(call, turn, confirmed = false)
            }
            ToolFamily.HEALTH -> {
                val call = healthCall(transcript)
                Log.i(TAG, "ENGINE_BRANCH=follow_up_fast_path tool=${call.name}")
                remoteChatState.setLastRoute("LOCAL (bypass: follow-up capability)")
                turn.routingPath = "CAPABILITY_FAST_PATH"
                turn.modelRounds = 0
                executeAndTrack(call, turn, confirmed = false)
            }
            else -> null
        }
    }

    /** Runs [call] through [ToolRouter], tracks it for [ConversationManager], and turns the outcome into speech. */
    private suspend fun executeAndTrack(call: ToolCall, turn: TurnState, confirmed: Boolean): String {
        turn.toolsRequested += call.name
        // § FASE 2A.6 §1 — a deterministic path (pending confirmation/
        // disambiguation, fast path, structured agenda, capability fast
        // path) never goes through `runBrainLoop`'s own tracking, so it
        // marks its own call's family as required here — always trivially
        // satisfied below on success, never on failure, exactly like a
        // model-requested tool call would be.
        RelevantToolSelector.familyOf(call.name)?.let { fam ->
            if (fam in GROUNDED_FAMILIES) turn.requiredGroundingFamilies = turn.requiredGroundingFamilies + fam
            // § FASE 2A.8 RELEASE GATE A — remembered even before the call
            // resolves: a follow-up like "E domani?" after a WEATHER attempt
            // that then failed (e.g. offline) should still try WEATHER
            // again, not fall through with nothing to resolve against.
            if (fam in FOLLOW_UP_CAPABLE_FAMILIES) conversationManager.noteCapabilityTopic(fam)
        }
        val outcome = toolRouter.execute(call, turn.budget, online = isOnline(), confirmed = confirmed)
        conversationManager.onToolExecuted(call, outcome)
        return when (outcome) {
            is ToolOutcome.Done -> {
                turn.toolsExecuted += call.name
                RelevantToolSelector.familyOf(call.name)?.let { turn.satisfiedGroundingFamilies += it }
                outcome.spoken
            }
            is ToolOutcome.Failed -> {
                turn.toolsFailed += call.name
                turn.toolFailureCodes += outcome.code
                outcome.spoken
            }
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
        // § logging temporaneo obbligatorio, audit "Conversational mode non
        // tenta più Core" — se questo NON compare per un messaggio inviato
        // davvero, il turno è stato intercettato prima (vedi i vari
        // ENGINE_BRANCH= sopra), mai da un problema dentro JarvisBrain stesso.
        Log.i(TAG, "ENGINE_BRANCH=brain_loop")
        // § FASE 2A.8 RELEASE GATE A/C — remembered BEFORE the model answers
        // (it will answer this conceptually, from its own knowledge, since no
        // DEVICE_INFO family match exists for a bare "che differenza c'è tra
        // RAM e VRAM?" — see RelevantToolSelector's own doc comment on why
        // that keyword set is deliberately quantity-phrase-only) so a
        // following bare partitive ("Quanta ne ho?") has something to
        // resolve against.
        DeviceInfoFollowUp.extractTopic(transcript)?.let { conversationManager.noteKnowledgeTopic(it) }
        val reasoningMode = settings.jarvisReasoningMode.first()
        val slot = brain.resolveSlot(reasoningMode, transcript)
        val assembled = contextAssembler.assemble(transcript, conversationManager.snapshotText())
        turn.memoriesRetrieved = assembled.memoriesRetrieved
        // § FASE 2A.5 diagnostica richiesta esplicitamente ("quantità... di
        // history/context inserita") — a size only, never the text itself.
        turn.contextBlockChars = assembled.text.length
        var contextBlock = assembled.text
        // The structured path already tried a literal name match against the
        // calendar and missed — a real entry may still exist under different
        // wording, so nudge the model towards list_agenda + a real id instead
        // of repeating the same literal lookup itself.
        turn.structuredMissHint?.let { hint ->
            contextBlock = (
                "Una ricerca diretta per nome nel calendario non ha trovato una corrispondenza esatta " +
                    "($hint). Se la richiesta riguarda un impegno esistente, usa prima list_agenda per vedere " +
                    "i titoli reali e trova quello giusto prima di agire, invece di inventare un id.\n\n" + contextBlock
            ).trim()
        }
        var currentText = transcript
        var rounds = 0

        while (true) {
            rounds++
            turn.rounds = rounds
            if (rounds > MAX_BRAIN_ROUNDS) return CANNED_ERROR
            val timeoutSeconds = if (rounds == 1) DEFAULT_GENERATION_TIMEOUT_SECONDS else FOLLOWUP_TIMEOUT_SECONDS

            // § FASE 2A.3: tool relevance is decided from the turn's ORIGINAL
            // `transcript`, never from `currentText` once it becomes a
            // synthetic "Risultato degli strumenti eseguiti: ..." follow-up
            // (round 2+) — see `JarvisBrain.reply`'s `toolSelectionText` doc
            // comment for why selecting from that text instead would starve
            // a later round of tools the original request might still need.
            val reply = brain.reply(currentText, contextBlock, slot, timeoutSeconds, toolSelectionText = transcript)
            // § FASE 2A.5 diagnostica richiesta esplicitamente ("tool family
            // selezionata", "tool disponibili al modello") — read right after
            // the call it describes, same "set by the last call" convention
            // JarvisBrain already uses (see PromptDiagnostics' doc comment).
            // Stable across rounds by construction (same toolSelectionText
            // every round, § FASE 2A.4), so overwriting each round is harmless.
            brain.lastPromptDiagnostics?.let { diag ->
                turn.toolFamiliesSelected = diag.toolFamilies
                turn.availableToolCount = diag.availableToolCount
                turn.selectedToolCount = diag.selectedToolCount
                // § FASE 2A.6 §1 — set once, from round 1: `toolSelectionText`
                // is the same original `transcript` every round (§ FASE 2A.4),
                // so the SPECIFIC families it maps to are stable for the whole
                // turn, same as `toolFamiliesSelected` already is. Only the
                // families the request SPECIFICALLY named (never the
                // ambiguous-fallback whole catalog) become "required".
                if (rounds == 1) {
                    turn.requiredGroundingFamilies = diag.specificFamilies
                        .mapNotNull { name -> ToolFamily.entries.find { it.name == name } }
                        .filter { it in GROUNDED_FAMILIES }
                        .toSet()
                }
            }
            if (reply is BrainReply.Unavailable) {
                // JarvisEngineRouter is expected to have already kept us from being
                // called at all in this case; this is the honest fallback if the
                // model unloads mid-turn regardless.
                turn.fallbackOccurred = true
                return CANNED_ERROR
            }
            val ready = reply as BrainReply.Ready
            turn.modelRounds = rounds
            turn.parseOutcomesByRound += ready.parseOutcome.name
            // § FASE 2A.5-bis: only a genuine MALFORMED_JSON round counts as
            // a real parse error — PLAIN_TEXT is the model correctly
            // answering without JSON because no tool was needed, the common
            // case, not a failure (see ParseOutcome's own doc comment).
            if (ready.parseOutcome == ParseOutcome.MALFORMED_JSON) turn.parseError = true
            turn.parseOutcome = ready.parseOutcome.name
            if (turn.firstEmitAt == null) turn.firstEmitAt = System.currentTimeMillis()

            val response = ready.response
            if (response.toolCalls.isEmpty()) {
                // § FASE 2A.7 — the exact fail-closed invariant this phase's
                // predecessor enforced inline is now [GroundingGate] (`:core`,
                // pure, tested by GroundingGateTest's cases A-F): a genuinely
                // malformed output (the model tried to produce protocol JSON
                // and failed — `response.assistantText` here is literally
                // `parsed.rawText`, i.e. possibly a raw, truncated protocol-
                // JSON fragment, the exact "raw JSON shown in chat" bug found
                // for "Accendi la luce della camera") is blocked BEFORE
                // grounding; then every family the request SPECIFICALLY
                // required real data for must have a successfully-executed
                // tool of that exact family, or the plain-text answer is
                // never returned. Same messages, same precedence as before —
                // this call replaces the two inline `if`s, it does not change
                // what they decided.
                when (
                    val decision = GroundingGate.decide(
                        parseOutcome = ready.parseOutcome,
                        requiredFamilies = turn.requiredGroundingFamilies.map { it.name }.toSet(),
                        satisfiedFamilies = turn.satisfiedGroundingFamilies.map { it.name }.toSet(),
                    )
                ) {
                    is GroundingGate.Decision.Block -> {
                        turn.groundingBlockReason = decision.reason
                        return if (decision.reason == GroundingGate.MALFORMED_JSON_REASON) {
                            MALFORMED_OUTPUT_MESSAGE
                        } else {
                            GROUNDING_FAIL_CLOSED_MESSAGE
                        }
                    }
                    GroundingGate.Decision.Allow -> return response.assistantText.trim().ifBlank { "Fatto." }
                }
            }

            val toolResults = StringBuilder()
            var confirmationPrompt: String? = null
            for (call in response.toolCalls) {
                turn.toolsRequested += call.name
                if (turn.budget.exhausted) {
                    toolResults.append("Ho eseguito il numero massimo di operazioni per questo turno.\n")
                    break
                }
                // § FASE 2A.8 RELEASE GATE A — same topic bookkeeping as
                // executeAndTrack's deterministic paths, so a model-driven
                // WEATHER/AGENDA/HEALTH tool call ALSO leaves a follow-up
                // topic behind, not just the capability-fast-path ones.
                RelevantToolSelector.familyOf(call.name)?.let { fam ->
                    if (fam in FOLLOW_UP_CAPABLE_FAMILIES) conversationManager.noteCapabilityTopic(fam)
                }
                when (val outcome = toolRouter.execute(call, turn.budget, online = isOnline(), confirmed = false)) {
                    is ToolOutcome.Done -> {
                        turn.toolsExecuted += call.name
                        // § FASE 2A.6 §1 rule 5 — only the FAMILY the request
                        // specifically required can satisfy it; a tool from an
                        // unrelated family executing successfully never does.
                        RelevantToolSelector.familyOf(call.name)?.let { turn.satisfiedGroundingFamilies += it }
                        conversationManager.onToolExecuted(call, outcome)
                        toolResults.append(outcome.spoken).append('\n')
                    }
                    is ToolOutcome.Failed -> {
                        turn.toolsFailed += call.name
                        turn.toolFailureCodes += outcome.code
                        toolResults.append(outcome.spoken).append('\n')
                    }
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

        // § FASE 2A.5 diagnostica richiesta esplicitamente — see EngineTurnDiagnostics' own doc comment.
        val toolsFailed = ArrayList<String>()
        var toolFamiliesSelected: List<String> = emptyList()
        var availableToolCount = 0
        var selectedToolCount = 0
        var rounds = 1
        var contextBlockChars = 0
        // § FASE 2A.5-bis diagnostica richiesta esplicitamente — see EngineTurnDiagnostics' own doc comment.
        var parseOutcome: String = ParseOutcome.VALID.name

        /** Set by [runStructuredPath] on a lexical miss, read by [runBrainLoop]. */
        var structuredMissHint: String? = null

        // § FASE 2A.6 diagnostica v2 richiesta esplicitamente — see EngineTurnDiagnostics' own doc comment.
        var routingPath: String = "LLM_LOOP"
        var modelRounds = 0
        val parseOutcomesByRound = ArrayList<String>()
        /** [ToolFamily]s the request SPECIFICALLY required real data for — grown by [executeAndTrack] for deterministic paths, by [runBrainLoop] from round 1's `PromptDiagnostics.specificFamilies`. */
        var requiredGroundingFamilies: Set<ToolFamily> = emptySet()
        /** Of [requiredGroundingFamilies], the ones a real tool of that exact family actually executed successfully for — the ONLY way to satisfy one (§ FASE 2A.6 §1 rules 1-6). */
        val satisfiedGroundingFamilies = mutableSetOf<ToolFamily>()
        var groundingBlockReason: String? = null
        val toolFailureCodes = ArrayList<String>()
        var networkAvailable: Boolean? = null

        /** Never includes the reply text itself — only counts/booleans, per [EngineTurnDiagnostics]'s contract. */
        fun toDiagnostics(): EngineTurnDiagnostics {
            val now = System.currentTimeMillis()
            // § FASE 2A.6 §1 — no longer a heuristic computed after the fact:
            // `requiredGroundingFamilies`/`satisfiedGroundingFamilies` are the
            // exact sets `executeAndTrack`/`runBrainLoop` maintained (and
            // enforced fail-closed against) while the turn actually ran.
            val groundingRequired = requiredGroundingFamilies.isNotEmpty()
            val groundingSatisfied = requiredGroundingFamilies.isEmpty() ||
                satisfiedGroundingFamilies.containsAll(requiredGroundingFamilies)
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
                toolFamiliesSelected = toolFamiliesSelected,
                availableToolCount = availableToolCount,
                selectedToolCount = selectedToolCount,
                toolsFailed = toolsFailed,
                rounds = rounds,
                contextBlockChars = contextBlockChars,
                parseOutcome = parseOutcome,
                groundingRequired = groundingRequired,
                groundingSatisfied = groundingSatisfied,
                routingPath = routingPath,
                modelRounds = modelRounds,
                parseOutcomesByRound = parseOutcomesByRound,
                requiredGroundingFamilies = requiredGroundingFamilies.map { it.name },
                satisfiedGroundingFamilies = satisfiedGroundingFamilies.map { it.name },
                groundingBlockReason = groundingBlockReason,
                toolFailureCodes = toolFailureCodes,
                networkAvailable = networkAvailable,
            )
        }
    }

    private companion object {
        const val TAG = "JarvisConversational"

        // Bounded so a flaky multi-round turn cannot silently run for minutes:
        // worst case is now DEFAULT_GENERATION_TIMEOUT_SECONDS (round 1) plus
        // (MAX_BRAIN_ROUNDS - 1) * FOLLOWUP_TIMEOUT_SECONDS, ~180s instead of
        // the previous up-to-6-rounds-at-90s-each (~9 minutes). 4 rounds gives
        // the structured-path-miss recovery (list_agenda -> act -> compose the
        // final sentence, 3 rounds) one round of margin.
        const val MAX_BRAIN_ROUNDS = 4
        const val FOLLOWUP_TIMEOUT_SECONDS = 30L

        const val MAX_DIAGNOSTICS_HISTORY = 20
        const val CANNED_ERROR =
            "Non sono riuscito a completare la richiesta in modalità conversazionale. Riprova, per favore."

        // § FASE 2A.6 §1/§6 — the two fail-closed, honest fallback messages:
        // never a fabricated fact, never a raw protocol/JSON fragment.
        const val GROUNDING_FAIL_CLOSED_MESSAGE = "Non riesco ad accedere a quel dato in questo momento."
        const val MALFORMED_OUTPUT_MESSAGE =
            "Non sono riuscito a formulare una risposta chiara. Puoi ripetere la richiesta?"

        // § FASE 2A.7/2A.8 RELEASE GATE 3/H — honest, deterministic answer for
        // a forecast further out than `WeatherDaysAhead.MAX_SUPPORTED_DAYS_AHEAD`
        // (raised to 16 in FASE 2A.8 §H — this message's own day count tracks
        // that constant, never a silently-clamped nearer-day forecast instead).
        const val WEATHER_OUT_OF_RANGE_MESSAGE =
            "Riesco a prevedere il meteo solo fino a ${WeatherDaysAhead.MAX_SUPPORTED_DAYS_AHEAD} giorni da oggi: non ho una previsione così lontana."

        // § FASE 2A.8 RELEASE GATE A — the families whose date-driven
        // capability call can be re-invoked from a bare date follow-up
        // ("E dopodomani?") via `runFollowUpFastPath`, without the request
        // repeating a family keyword of its own.
        val FOLLOW_UP_CAPABLE_FAMILIES = setOf(ToolFamily.WEATHER, ToolFamily.AGENDA, ToolFamily.HEALTH)
    }
}
