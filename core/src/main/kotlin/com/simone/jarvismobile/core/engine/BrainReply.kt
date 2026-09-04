package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.protocol.AssistantResponse
import com.simone.jarvismobile.core.protocol.ParseResult

/**
 * The real cause behind a turn's parse result (§ FASE 2A.5-bis AUDIT PARSE
 * ERROR — "individua ESATTAMENTE cosa significa parseError=true"). A bare
 * boolean conflated two very different situations: the protocol's own
 * contract lets the model answer in plain text whenever it decides no tool
 * is needed — the common case, not a failure at all — and a genuine failure,
 * where the model tried to produce JSON and it still could not be decoded.
 * [fromParseResult] is the one place this classification happens, so
 * `JarvisBrain`/diagnostics never re-derive it differently.
 */
enum class ParseOutcome {
    /** Decoded on the first attempt — the normal, most common case. */
    VALID,

    /** First decode failed but the single controlled repair succeeded. */
    REPAIRED,

    /**
     * Plain conversational text, produced on purpose — no `{`/`}` shape was
     * even attempted. Not an error: most turns (no tool needed) look exactly
     * like this by design.
     */
    PLAIN_TEXT,

    /**
     * The model's output contained a `{...}`-shaped fragment that still
     * could not be decoded even after the repair attempt — a genuine
     * protocol-following failure, the only case diagnostics should actually
     * flag as `parseError`.
     */
    MALFORMED_JSON,
    ;

    companion object {
        fun fromParseResult(result: ParseResult): ParseOutcome = when (result) {
            is ParseResult.Valid -> VALID
            is ParseResult.Repaired -> REPAIRED
            is ParseResult.PlainText -> if (result.looksLikeAttemptedJson) MALFORMED_JSON else PLAIN_TEXT
        }
    }
}

/**
 * What `JarvisBrain.reply()` returns for one model turn — a closed result
 * instead of a nullable [AssistantResponse], so "the model produced valid
 * structured output" and "the model is unavailable" are never conflated (the
 * one case `ConversationalJarvisEngine` treats as a Classic-engine fallback
 * signal, at the `JarvisEngineRouter` layer) and the real [parseOutcome] is
 * visible for diagnostics without a second, stateful call.
 */
sealed interface BrainReply {
    /**
     * A response was produced. [parsedCleanly] is false when
     * `ResponseParser` had to fall back to plain text (see its own
     * documented contract: one repair attempt, then plain text, never a
     * tool call on invalid JSON) — kept for source compatibility with
     * existing callers; [parseOutcome] is the real, cause-carrying signal
     * (§ FASE 2A.5-bis) callers should prefer for diagnostics.
     */
    data class Ready(
        val response: AssistantResponse,
        val parsedCleanly: Boolean,
        val parseOutcome: ParseOutcome = if (parsedCleanly) ParseOutcome.VALID else ParseOutcome.PLAIN_TEXT,
    ) : BrainReply

    /** The local model itself is not loaded/available. */
    data object Unavailable : BrainReply
}
