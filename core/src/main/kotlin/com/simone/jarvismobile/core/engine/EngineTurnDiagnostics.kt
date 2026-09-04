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
)
