package com.simone.jarvismobile.core.remote

/**
 * Where a single conversational turn should be answered. This is a narrower,
 * different decision than [com.simone.jarvismobile.core.routing.RouteTarget]
 * (which chooses between a deterministic tool, Home Assistant, LOCAL or
 * REMOTE_PC for a whole transcript, upstream of this): AiTarget only fires
 * once a message has already been confirmed as an ordinary conversational/
 * knowledge question with no matching tool, and decides which brain — local,
 * or which one of Core's two remote models — answers it.
 */
enum class AiTarget { LOCAL, REMOTE_FAST, REMOTE_BRAIN }

/** Everything [AiRouter] needs to decide, and nothing it has to fetch itself — keeps it pure and trivially testable. */
data class AiRoutingInput(
    val coreEnabled: Boolean,
    val coreState: CoreConnectionState,
    val networkAvailable: Boolean,
    /** Same signal already used for local FAST/ADVANCED routing (ComplexityHeuristic.needsReasoning). */
    val needsReasoning: Boolean,
    /** Set when a remote attempt already failed earlier in this same turn, so the router does not try Core twice. */
    val remoteAlreadyFailedThisTurn: Boolean = false,
)

/**
 * Deterministic, LLM-free router (task rule: "Non usare un secondo LLM per
 * decidere il routing"). Decision order, matching the task's own spec:
 *
 *  1. Core disabled/not configured           -> LOCAL
 *  2. no network, or Core not ONLINE          -> LOCAL
 *  3. a remote attempt already failed this turn -> LOCAL (never retried automatically — no double generation)
 *  4. otherwise: simple request  -> REMOTE_FAST, complex request -> REMOTE_BRAIN
 *
 * DEGRADED is treated the same as OFFLINE here: a mismatched protocol version
 * or llmAvailable=false means Core cannot be trusted to answer, not merely
 * that it is slow.
 */
object AiRouter {
    fun decide(input: AiRoutingInput): AiTarget {
        if (!input.coreEnabled) return AiTarget.LOCAL
        if (!input.networkAvailable) return AiTarget.LOCAL
        if (input.coreState != CoreConnectionState.ONLINE) return AiTarget.LOCAL
        if (input.remoteAlreadyFailedThisTurn) return AiTarget.LOCAL
        return if (input.needsReasoning) AiTarget.REMOTE_BRAIN else AiTarget.REMOTE_FAST
    }
}
