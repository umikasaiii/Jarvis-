package com.simone.jarvismobile.core.llm

import java.util.concurrent.atomic.AtomicLong

/**
 * M3-C — a token figure that came from the RUNTIME, with its provenance. LiteRT-LM 0.14.0 (javap of the exact AAR in CI) exposes
 * `Conversation.getTokenCount()`, `BenchmarkInfo.getLastPrefillTokenCount()/getLastDecodeTokenCount()` and `EngineConfig.getMaxNumTokens()`;
 * it exposes NO pre-tokenisation of an arbitrary prompt. A post-turn conversation count therefore:
 *  - is read AFTER the native call returned and BEFORE the conversation may be closed (same lane), never later;
 *  - is the runtime's own count for THAT conversation (its semantics — total held in context vs. last turn — are not documented, so it is
 *    recorded as [Source.CONVERSATION_COUNT] and never used to decide what fits in the prompt);
 *  - does not replace the estimate that [ContextBudget] uses to build the prompt (ContextFit stays on the estimate);
 *  - is tied to one slot, one epoch (conversation generation) and one request.
 */
data class TokenMeasurement(
    val requestId: String,
    val slot: String,
    val epoch: Long,
    /** Monotonic number of the native call inside the request (1 = first generation). */
    val callIndex: Int,
    val kind: String,
    val source: Source,
    /** The count as the runtime reported it. */
    val tokens: Int,
    /** The window the runtime was CONFIGURED with (`maxNumTokens`), when known — never assumed universal. */
    val configuredWindow: Int?,
    val at: Long,
) {
    enum class Source { CONVERSATION_COUNT, LAST_PREFILL_COUNT, LAST_DECODE_COUNT }
    /** `measured` is true only for runtime-sourced figures; an estimate never reaches this type. */
    val measured: Boolean get() = true
}

/** Collects the measurements of ONE request (per-request object: no global "last value" that a concurrent request could overwrite). */
class TokenLedger(val requestId: String, private val capacity: Int = 12) {
    private val items = ArrayList<TokenMeasurement>()
    private val calls = java.util.concurrent.atomic.AtomicInteger(0)
    @Synchronized fun record(m: TokenMeasurement) { if (items.size < capacity) items += m }
    fun nextCallIndex(): Int = calls.incrementAndGet()
    @Synchronized fun all(): List<TokenMeasurement> = items.toList()
    /** The latest figure of the request, or null (measurement missing: the caller keeps its estimate and says measured=false). */
    @Synchronized fun latest(source: TokenMeasurement.Source = TokenMeasurement.Source.CONVERSATION_COUNT): TokenMeasurement? = items.lastOrNull { it.source == source }
}

/** `getLastPrefillTokenCount/getLastDecodeTokenCount` are per-turn figures of the runtime, kept apart from the conversation count; none of them is a tokenizer. */

/** Compares the window JARVIS configured for the runtime with the one ContextBudget plans for, without changing either. */
object ContextWindowCheck {
    data class Result(val configured: Int?, val planned: Int, val consistent: Boolean, val note: String)

    fun check(configuredWindow: Int?, plannedWindow: Int): Result = when {
        configuredWindow == null -> Result(null, plannedWindow, false, "runtime window unknown: not assumed equal to the planned $plannedWindow")
        configuredWindow == plannedWindow -> Result(configuredWindow, plannedWindow, true, "configured window equals the planned window")
        configuredWindow < plannedWindow -> Result(configuredWindow, plannedWindow, false, "runtime window ($configuredWindow) is SMALLER than the planned window ($plannedWindow): the prompt may not fit")
        else -> Result(configuredWindow, plannedWindow, false, "runtime window ($configuredWindow) is larger than the planned window ($plannedWindow): planning is conservative")
    }
}
