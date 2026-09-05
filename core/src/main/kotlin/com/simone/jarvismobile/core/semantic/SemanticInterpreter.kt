package com.simone.jarvismobile.core.semantic

/**
 * The previously-resolved state this turn may (only ever partially) inherit
 * from — never a raw transcript, never model memory: [previousFrame] is the
 * last [SemanticFrame] that actually passed validation+merge, exactly the
 * "a few resolved previous SemanticFrames" state §5 asks for. [previousFrame]
 * is null once it has gone stale (idle timeout owned by the caller, e.g.
 * `ConversationManager`) — the interpreter itself never guesses an age.
 */
data class SemanticDialogueContext(
    val previousFrame: SemanticFrame?,
)

/**
 * Strict, closed-vocabulary result of one [SemanticInterpreter.interpret]
 * call (§ FASE 2A.9 §4: "invalid semantic output must NEVER cause a random
 * capability call"). [Invalid] is not an exception — a bad/ambiguous/
 * unparsable model output is an ordinary, expected outcome that routes to a
 * safe fallback, never a crash.
 */
sealed interface SemanticInterpretation {
    data class Valid(val frame: SemanticFrame) : SemanticInterpretation
    data class Invalid(val reason: String) : SemanticInterpretation
}

/**
 * § FASE 2A.9 — the layer between raw user language and routing (spec's
 * central request): `USER TEXT → SemanticInterpreter → SemanticFrame →
 * router → capability/tool → response`.
 *
 * Root cause this closes: `ConversationalJarvisEngine`'s old
 * `runFollowUpFastPath` decided "this bare follow-up means the same
 * capability as last time" purely from the ABSENCE of a keyword plus the
 * PRESENCE of a leftover topic — with zero check on whether the current
 * turn's own words are actually consistent with that topic ("Domani farà
 * caldo?" after a HEALTH turn was answered from Health Connect, because
 * "domani" looks date-shaped and HEALTH was the last topic). A
 * [SemanticInterpreter] implementation must never repeat that shape: it
 * interprets the CURRENT turn on its own merit first: the router
 * (`SemanticFrameMerger`) resolves inheritance afterward, not the other way
 * around.
 *
 * An implementation MUST NOT itself answer the user, execute a tool, or
 * author a side effect — only transform natural language into structure.
 * Any implementation may be swapped later (a smaller model, a dedicated
 * classifier, JARVIS Core) without touching a caller, because callers only
 * ever depend on this interface — never on a concrete engine/model name
 * (§ FASE 2A.9 §11: "not `if (modelName == \"gemma...\")`").
 */
interface SemanticInterpreter {
    suspend fun interpret(text: String, dialogueContext: SemanticDialogueContext): SemanticInterpretation
}

/** Where a turn's resolved semantics actually came from (§ FASE 2A.9 §15 diagnostics). */
enum class SemanticSource {
    /** A truly unambiguous, very-high-confidence deterministic command — no interpreter call made at all. */
    HARD_DETERMINISTIC,

    /** [SemanticInterpreter] produced a [SemanticInterpretation.Valid] frame that was used. */
    LOCAL_INTERPRETER,

    /** The interpreter failed/was unavailable/produced [SemanticInterpretation.Invalid] — the old keyword/topic path answered instead. */
    LEGACY_FALLBACK,

    /** Neither of the above resolved the turn — it went to the full reasoning/tool-calling loop. */
    LLM_FALLBACK,
}
