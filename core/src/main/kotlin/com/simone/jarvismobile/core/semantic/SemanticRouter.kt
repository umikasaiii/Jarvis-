package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.tools.ToolFamily

/**
 * § FASE 2A.9.1 — the ownership decision for ONE already-merged,
 * VALIDATED [SemanticFrame]: is its domain one this layer may resolve
 * DIRECTLY against a capability builder, or must it be handed off to the
 * full reasoning/tool-calling loop (`runBrainLoop`)? Deliberately has NO
 * third "legacy" case — see the class doc comment on [SemanticRouter] for
 * why a valid frame can never legitimately produce one.
 */
sealed interface SemanticRoutingOutcome {
    /** [domain] is one of [SemanticRouter.DIRECTLY_ROUTABLE_DOMAINS] — the caller may attempt a direct capability call. Attempting it may still fail (e.g. an unresolvable metric); a failure there is STILL [HandoffToLlm], never a reclassification by keyword. */
    data class Direct(val domain: ToolFamily) : SemanticRoutingOutcome

    /** Send to the existing reasoning/tool-calling loop as-is — never re-decided by keyword/topic matching. */
    data object HandoffToLlm : SemanticRoutingOutcome
}

/**
 * § FASE 2A.9.1 FIX SEMANTIC ROUTING OWNERSHIP — root cause this closes:
 * `ConversationalJarvisEngine.runSemanticPath` used to return a bare
 * `String?`, so "the interpreter failed/was invalid" and "the interpreter
 * succeeded but the frame must be delegated elsewhere" were BOTH spelled
 * `null` — indistinguishable to the caller. The engine's dispatch chain then
 * treated every `null` identically: fall through to the OLD keyword/topic
 * fast paths (`runCapabilityFastPath`/`runFollowUpFastPath`). A perfectly
 * valid `KNOWLEDGE_QUERY`/`CONVERSATION`/`MULTI_SOURCE_REASONING` frame (or
 * a valid `CAPABILITY_QUERY` with a non-read-only operation, an ambiguous
 * domain, or a domain this router doesn't resolve directly) could therefore
 * still be silently reclassified by the very keyword matching this whole
 * phase demoted to a fallback role — violating the permanent rule
 * "CURRENT TURN SEMANTIC MEANING > LEGACY KEYWORD ROUTING" for every one of
 * those cases, not just the ones this router happens to resolve.
 *
 * [SemanticRoutingOutcome] makes the two "valid but delegated" shapes
 * (`Direct`/`HandoffToLlm`) the ONLY things a validated frame can produce —
 * a `LegacyFallback` disposition is reachable only one level up, by the
 * caller, and only for a frame that never validated at all (interpreter
 * unavailable/threw, or [SemanticFrameValidator] rejected it) — see
 * `ConversationalJarvisEngine.runSemanticPath`'s own doc comment for the
 * full four-way split (`Answer`/`HandoffToLlm`/`LegacyFallback`).
 */
object SemanticRouter {

    /** The only domains this layer ever resolves directly — reused verbatim from `ConversationalJarvisEngine.routeSemanticCapability`, never a second list to keep in sync (that class' `when` is exhaustive over the SAME four plus `else -> null`). */
    val DIRECTLY_ROUTABLE_DOMAINS: Set<ToolFamily> = setOf(
        ToolFamily.WEATHER, ToolFamily.HEALTH, ToolFamily.AGENDA, ToolFamily.DEVICE_INFO,
    )

    fun routeFrame(frame: SemanticFrame): SemanticRoutingOutcome {
        // § FASE 2A.9.1 semantica obbligatoria #6 — never built here, never
        // duplicated: the caller reads `frame.domains` itself (still intact
        // on the frame) to seed `runBrainLoop`'s grounding requirement.
        if (frame.intent == SemanticIntent.MULTI_SOURCE_REASONING) return SemanticRoutingOutcome.HandoffToLlm

        // § #4/#5/#8 — KNOWLEDGE_QUERY/CONVERSATION/CLARIFICATION/UNKNOWN/
        // DIRECT_COMMAND (the last should never reach here — HARD paths
        // already claim it — but is handled the same safe way regardless)
        // are never this router's job.
        if (frame.intent != SemanticIntent.CAPABILITY_QUERY) return SemanticRoutingOutcome.HandoffToLlm

        // § #7 — a non-read-only operation is NEVER authorized here; it goes
        // to the existing tool-calling loop, where `ToolRouter`/
        // `ToolCallBudget`/confirmation remain fully authoritative.
        if (frame.operation != SemanticOperation.UNKNOWN && frame.operation !in READ_ONLY_OPERATIONS) {
            return SemanticRoutingOutcome.HandoffToLlm
        }

        // § #8 — an ambiguous (0 or 2+) domain set, or one outside the four
        // this layer resolves directly, is a valid frame this router simply
        // doesn't own — handed to the model, never reclassified by keyword.
        val domain = frame.domains.singleOrNull() ?: return SemanticRoutingOutcome.HandoffToLlm
        if (domain !in DIRECTLY_ROUTABLE_DOMAINS) return SemanticRoutingOutcome.HandoffToLlm

        return SemanticRoutingOutcome.Direct(domain)
    }
}
