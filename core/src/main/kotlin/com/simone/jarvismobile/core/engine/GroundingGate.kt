package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.tools.ToolOutcomeStatus

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
 *
 * § JARVIS Implementation Master Plan — PASSAGGIO 2 extends [decide] (never
 * replaces it — every existing 3-argument call site, including this class'
 * own test suite, keeps compiling and behaving identically via the new
 * parameters' defaults) to consume real [ToolOutcomeStatus] evidence per
 * family when the caller has it, instead of reasoning ONLY from the boolean
 * "was this family satisfied by ANY successful call" [satisfiedFamilies] set.
 * [evidenceByFamily] is intentionally a `Map<String, ToolOutcomeStatus>`
 * keyed the same way as [requiredFamilies]/[satisfiedFamilies] (plain family
 * names) so this object stays agnostic of which module defines the family
 * enum — a family absent from the map falls back to the original boolean
 * check, so a caller that never migrated to structured evidence sees no
 * behavior change at all.
 */
object GroundingGate {

    /** What should happen to a model round whose response had no tool calls. */
    sealed interface Decision {
        /** The response may be returned to the user as-is. */
        data object Allow : Decision

        /**
         * The response must NOT reach the user; [reason] is the technical,
         * non-personal cause (for diagnostics only — kept byte-for-byte
         * identical to the pre-PASSAGGIO-2 shape so existing callers/tests
         * that only read [reason] are unaffected). [unmetDetails] (§
         * PASSAGGIO 2 §4, "distinct controlled outcomes") is the SAME unmet
         * families, additionally paired with the real [ToolOutcomeStatus]
         * that made each one unmet when the caller had one — `null` for a
         * family that had no structured evidence at all (legacy path) or for
         * the [MALFORMED_JSON_REASON] case (empty list, no family is "unmet"
         * there — the block is unconditional).
         */
        data class Block(val reason: String, val unmetDetails: List<UnmetFamily> = emptyList()) : Decision
    }

    /** One family that failed to ground this turn, and why — see [Decision.Block.unmetDetails]. */
    data class UnmetFamily(val family: String, val status: ToolOutcomeStatus?)

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
     *  2. Otherwise, every family in [requiredFamilies] is checked in order:
     *     - If [evidenceByFamily] has a status for it (§ PASSAGGIO 2): a
     *       [ToolOutcomeStatus.SUCCESS_DATA]/[ToolOutcomeStatus.SUCCESS_EMPTY]/
     *       [ToolOutcomeStatus.PARTIAL] status satisfies it (SUCCESS_EMPTY is
     *       a genuine, successful answer, never treated as failure — § the
     *       exact bug PASSAGGIO 1 fixed at the tool level; PARTIAL preserves
     *       whatever succeeded, never hidden because another part failed). A
     *       [ToolOutcomeStatus.STALE] status satisfies it ONLY when the
     *       family is also in [staleAllowedFamilies] — the fresh/stale USE
     *       decision stays with whichever capability/query understands that
     *       data's own semantics (§ PASSAGGIO 1 §8), never a universal TTL
     *       here. [ToolOutcomeStatus.DATA_UNAVAILABLE]/[ToolOutcomeStatus.PERMISSION_MISSING]/
     *       [ToolOutcomeStatus.SOURCE_FAILURE]/[ToolOutcomeStatus.TOOL_FAILURE]
     *       never satisfy it — these remain distinct, real failure reasons,
     *       never silently reinterpreted as "nothing to show".
     *     - Otherwise (no evidence for that family — the caller never
     *       migrated it, or no tool of that family ran at all): falls back to
     *       the original boolean check, `family in satisfiedFamilies` —
     *       unknown fidelity, never guessed as more than that (§ PASSAGGIO 1
     *       §18, "rappresentarlo come UNKNOWN/UNAVAILABLE piuttosto che
     *       inventare").
     *     Any family required but not satisfied blocks, listing every unmet
     *     family (not just the first), in [requiredFamilies]' own iteration
     *     order — same reason-string shape as before PASSAGGIO 2.
     *  3. Nothing required, or everything required was satisfied → allowed.
     */
    fun decide(
        parseOutcome: ParseOutcome,
        requiredFamilies: Set<String>,
        satisfiedFamilies: Set<String>,
        evidenceByFamily: Map<String, ToolOutcomeStatus> = emptyMap(),
        staleAllowedFamilies: Set<String> = emptySet(),
    ): Decision {
        if (parseOutcome == ParseOutcome.MALFORMED_JSON) return Decision.Block(MALFORMED_JSON_REASON)

        val unmet = mutableListOf<UnmetFamily>()
        for (family in requiredFamilies) {
            val status = evidenceByFamily[family]
            val satisfied = when (status) {
                null -> family in satisfiedFamilies
                ToolOutcomeStatus.SUCCESS_DATA, ToolOutcomeStatus.SUCCESS_EMPTY, ToolOutcomeStatus.PARTIAL -> true
                ToolOutcomeStatus.STALE -> family in staleAllowedFamilies
                ToolOutcomeStatus.DATA_UNAVAILABLE, ToolOutcomeStatus.PERMISSION_MISSING,
                ToolOutcomeStatus.SOURCE_FAILURE, ToolOutcomeStatus.TOOL_FAILURE,
                -> false
            }
            if (!satisfied) unmet += UnmetFamily(family, status)
        }
        if (unmet.isNotEmpty()) {
            return Decision.Block(
                UNMET_FAMILY_REASON_PREFIX + unmet.joinToString(",") { it.family },
                unmetDetails = unmet,
            )
        }
        return Decision.Allow
    }

    const val MALFORMED_JSON_REASON = "malformed_json_output"
    const val UNMET_FAMILY_REASON_PREFIX = "no_tool_call_for_required_family:"
}
