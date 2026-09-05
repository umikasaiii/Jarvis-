package com.simone.jarvismobile.core.engine

/**
 * One turn's worth of engine telemetry, for the existing Diagnostics screen.
 *
 * Every field is a count, a name, or a boolean — never spoken text or tool
 * arguments, so this type is always safe to log (`LogRedactor` conventions
 * still apply at the call site for anything derived from user content).
 *
 * [timeToFirstEmitMs] is honestly NOT a token-level time-to-first-token: the
 * underlying LLM stack (`LlmEngine.chat`) has no streaming/token-callback API,
 * so `JarvisBrain` only starts emitting sentence chunks after the whole reply
 * has already been generated. This records time-to-first-sentence-chunk after
 * a completed generation, and is named/documented as such everywhere it is
 * displayed so it is never misread as true streaming latency.
 */
data class EngineTurnDiagnostics(
    val engine: JarvisEngineMode,
    val fastPathHit: Boolean,
    val timeToFirstEmitMs: Long,
    val totalTurnMs: Long,
    val memoriesRetrieved: Int,
    val toolsRequested: List<String>,
    val toolsExecuted: List<String>,
    val fallbackOccurred: Boolean,
    val parseError: Boolean,
    val timestamp: Long,
    // § FASE 2A.5 diagnostica richiesta esplicitamente — additive, default
    // to the previous no-op values so no other constructor call site breaks.
    /** [com.simone.jarvismobile.core.tools.ToolFamily] names offered to the model this turn, e.g. `["AGENDA"]`. */
    val toolFamiliesSelected: List<String> = emptyList(),
    /** How many of `ToolRunner.available()`'s tools existed at all this turn — for context, never personal. */
    val availableToolCount: Int = 0,
    /** How many of those were actually shown to the model (`RelevantToolSelector`'s output size). */
    val selectedToolCount: Int = 0,
    /** Names the model requested that did NOT come back `ToolOutcome.Done` — the "successo/fallimento" signal. */
    val toolsFailed: List<String> = emptyList(),
    /** How many `JarvisBrain.reply()` rounds this turn actually used (1 = no follow-up round). */
    val rounds: Int = 1,
    /** `contextBlock`'s length in characters for this turn's first round — a size, never its content. */
    val contextBlockChars: Int = 0,
    // § FASE 2A.5-bis diagnostica richiesta esplicitamente ("parse error con
    // CAUSA, non solo boolean"; "groundingRequired"/"groundingSatisfied") —
    // additive, default to the previous no-op values.
    /** [ParseOutcome]'s name for the LAST round of this turn — the real cause behind [parseError], never just a boolean. */
    val parseOutcome: String = ParseOutcome.VALID.name,
    /** True when this turn's intent plausibly needed real/personal data (a [com.simone.jarvismobile.core.tools.ToolFamily] in `GROUNDED_FAMILIES` was offered, or a deterministic fast/structured path handled it). */
    val groundingRequired: Boolean = false,
    /** True when grounding was not required, or was required AND at least one tool actually ran this turn. False means the model likely answered a data question without checking anything. */
    val groundingSatisfied: Boolean = true,
    // § FASE 2A.6 diagnostica v2 richiesta esplicitamente — additive, default
    // to previous no-op values so no other constructor call site breaks.
    /**
     * Which branch of `ConversationalJarvisEngine.handle()` actually produced
     * this turn's reply — e.g. `PENDING_CONFIRMATION`, `PENDING_DISAMBIGUATION`,
     * `FAST_PATH`, `STRUCTURED_AGENDA`, `HOME_CONTROL_UNSUPPORTED`,
     * `CAPABILITY_FAST_PATH`, `LLM_LOOP`, `ERROR`. Never personal content, a
     * fixed vocabulary of route names.
     *
     * § FASE 2A.8 rename (was `"BRAIN"`): this only names WHICH ENGINE BRANCH
     * ran (`runBrainLoop`, the LLM-driven tool-call loop) — it says nothing
     * about which model actually answered inside that loop (LOCAL/REMOTE_FAST/
     * a future REMOTE_BRAIN). That is a separate axis, already tracked by
     * `RemoteChatState.lastRoute`/`lastAttempt.target` — conflating the two
     * under the single word "BRAIN" misread a REMOTE_FAST-answered turn as if
     * the not-yet-active BRAIN reasoning tier had run it.
     */
    val routingPath: String = "LLM_LOOP",
    /** How many `JarvisBrain.reply()` rounds actually ran the model (0 for any deterministic/capability path that never reaches it). */
    val modelRounds: Int = 0,
    /** [ParseOutcome] name per round the model actually ran, in order — a size/enum list, never the text itself. */
    val parseOutcomesByRound: List<String> = emptyList(),
    /** [com.simone.jarvismobile.core.tools.ToolFamily] names this turn's intent SPECIFICALLY required real data for (`RelevantToolSelector.matchedFamilies` ∩ `GROUNDED_FAMILIES`) — never the whole ambiguous-fallback catalog. */
    val requiredGroundingFamilies: List<String> = emptyList(),
    /** Of [requiredGroundingFamilies], the ones a real tool of that exact family actually executed successfully for. */
    val satisfiedGroundingFamilies: List<String> = emptyList(),
    /** Non-null only when [groundingSatisfied] is false or a malformed-output guard fired — the exact enforcement reason, e.g. `no_tool_call_for_required_family:WEATHER` or `malformed_json_output`. */
    val groundingBlockReason: String? = null,
    /** [ToolOutcome.Failed]'s technical `code` per failed tool, same order as [toolsFailed] — never the spoken failure message. */
    val toolFailureCodes: List<String> = emptyList(),
    /** Live connectivity this turn read for gating a `requiresNetwork` tool — `null` when never checked (no network-requiring tool was ever offered/attempted). */
    val networkAvailable: Boolean? = null,
    // § FASE 2A.9 SEMANTIC UNDERSTANDING LAYER diagnostica richiesta
    // esplicitamente (§15) — additive, default to previous no-op values so
    // no other constructor call site breaks. Never full message content,
    // sensitive arguments, personal Health values, or the model prompt.
    /** Whether a [com.simone.jarvismobile.core.semantic.SemanticInterpreter] was even attempted this turn. */
    val semanticEnabled: Boolean = false,
    /** [com.simone.jarvismobile.core.semantic.SemanticSource] name — which mechanism actually resolved this turn's meaning. */
    val semanticSource: String? = null,
    /** [com.simone.jarvismobile.core.semantic.SemanticIntent] name of the resolved (post-merge) frame, if any. */
    val semanticIntent: String? = null,
    /** [com.simone.jarvismobile.core.tools.ToolFamily] names of the resolved frame's domains. */
    val semanticDomains: List<String> = emptyList(),
    /** [com.simone.jarvismobile.core.semantic.SemanticOperation] name of the resolved frame, if any. */
    val semanticOperation: String? = null,
    /** [com.simone.jarvismobile.core.semantic.SemanticSlot] names the CURRENT turn's own words established. */
    val explicitSlots: List<String> = emptyList(),
    /** [com.simone.jarvismobile.core.semantic.SemanticSlot] names filled in from the previous frame by [com.simone.jarvismobile.core.semantic.SemanticFrameMerger]. */
    val inheritedSlots: List<String> = emptyList(),
    /** True when this turn's explicit domain took precedence over a different previous-turn domain (the §"permanent architectural rule" firing for real). */
    val currentOverridesPrevious: Boolean = false,
    /** True when the interpreter's output passed [com.simone.jarvismobile.core.semantic.SemanticFrameValidator]. */
    val semanticValid: Boolean = false,
    /** The interpreter's own reported confidence for this turn, or null if never attempted. */
    val semanticConfidence: Double? = null,
    /** Non-null only when [semanticValid] is false or the interpreter itself failed/timed out — the technical reason, never the raw model text. */
    val semanticFailureReason: String? = null,
    /** How long the semantic-interpreter call itself took, measured separately from tool/answer latency (§18). */
    val semanticLatencyMs: Long? = null,
)
