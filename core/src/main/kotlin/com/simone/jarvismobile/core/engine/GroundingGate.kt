package com.simone.jarvismobile.core.engine

/**
 * § FASE 2A.7 — extracted, pure form of the exact fail-closed invariant
 * [ConversationalJarvisEngine][com.simone.jarvismobile.engine.ConversationalJarvisEngine]'s
 * `runBrainLoop()` already enforced inline since FASE 2A.6, so the one
 * architectural guarantee this whole hardening effort exists for — "a
 * grounded request can never come back with an ungrounded or malformed
 * answer" — is a real, running JVM test instead of something only reviewable
 * by eye (this project has no Robolectric/instrumented infra to exercise the
 * Android engine class directly). This is a behavior-preserving refactor:
 * `runBrainLoop` calls [decide] instead of repeating the two `if` checks it
 * used to have inline, with identical reason strings/precedence.
 */
object GroundingGate {

    /** What should happen to a model round whose response had no tool calls. */
    sealed interface Decision {
        /** The response may be returned to the user as-is. */
        data object Allow : Decision

        /** The response must NOT reach the user; [reason] is the technical, non-personal cause (for diagnostics only). */
        data class Block(val reason: String) : Decision
    }

    /**
     * [requiredFamilies]/[satisfiedFamilies] are family names (the caller's
     * own enum `.name`s), kept as plain strings here so this stays agnostic
     * of which module/package defines the family enum. Precedence, exactly as
     * `runBrainLoop` already had it:
     *
     *  1. [parseOutcome] is `MALFORMED_JSON` → always blocked first, even if
     *     no family was required at all — a malformed/truncated protocol
     *     fragment must never reach the user regardless of grounding (§
     *     FASE 2A.6 §6, "Accendi la luce della camera" showed raw JSON even
     *     though DEVICE was never a grounded family).
     *  2. Otherwise, every family in [requiredFamilies] must also be in
     *     [satisfiedFamilies] (a family satisfied by an unrelated family's
     *     tool execution does NOT count — the caller is responsible for only
     *     ever adding a family to [satisfiedFamilies] after a real, matching
     *     tool executed successfully). Any family required but not satisfied
     *     blocks, listing every unmet family (not just the first), in
     *     [requiredFamilies]' own iteration order.
     *  3. Nothing required, or everything required was satisfied → allowed.
     */
    fun decide(
        parseOutcome: ParseOutcome,
        requiredFamilies: Set<String>,
        satisfiedFamilies: Set<String>,
    ): Decision {
        if (parseOutcome == ParseOutcome.MALFORMED_JSON) return Decision.Block(MALFORMED_JSON_REASON)

        val unmet = requiredFamilies - satisfiedFamilies
        if (unmet.isNotEmpty()) {
            return Decision.Block(UNMET_FAMILY_REASON_PREFIX + unmet.joinToString(","))
        }
        return Decision.Allow
    }

    const val MALFORMED_JSON_REASON = "malformed_json_output"
    const val UNMET_FAMILY_REASON_PREFIX = "no_tool_call_for_required_family:"
}
