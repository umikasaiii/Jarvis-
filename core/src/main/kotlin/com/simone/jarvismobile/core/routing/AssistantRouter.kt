package com.simone.jarvismobile.core.routing

import com.simone.jarvismobile.core.state.RouteTarget
import com.simone.jarvismobile.core.tools.SensitivityLevel

/** Snapshot of the world the router reasons about. Pure data, easy to fake. */
data class RoutingContext(
    val online: Boolean,
    val pcReachable: Boolean,
    val homeAssistantReachable: Boolean,
    val privacyProfile: PrivacyProfile,
    /** True if the transcript looks like it concerns personal memory. */
    val touchesMemory: Boolean,
    /** Rough complexity estimate (0..1) produced upstream. */
    val complexity: Double,
    val sensitivity: SensitivityLevel,
)

/** A deterministic command the router recognized without invoking any LLM. */
data class DeterministicMatch(val toolName: String, val confidence: Double)

interface AssistantRouter {
    fun route(transcript: String, context: RoutingContext): RoutingDecision
}

/**
 * Hybrid router (docs/ARCHITECTURE.md §13). Decision order:
 *  1. deterministic command  → RouteTarget.DETERMINISTIC (no LLM)
 *  2. home-assistant intent   → RouteTarget.HOME_ASSISTANT
 *  3. simple request          → local model
 *  4. memory request          → local retrieval + local model
 *  5. complex + PC allowed & reachable → remote PC (with local fallback)
 *  6. otherwise               → local model
 *
 * The router NEVER decides to ship the whole vault anywhere; memory sharing is
 * capped by the privacy profile and defaults to NONE for remote.
 */
class HybridRouter(
    private val commandMatcher: DeterministicCommandMatcher = DeterministicCommandMatcher(),
    private val complexityThreshold: Double = 0.6,
    private val localTimeoutMs: Long = 20_000L,
    private val remoteTimeoutMs: Long = 30_000L,
) : AssistantRouter {

    override fun route(transcript: String, context: RoutingContext): RoutingDecision {
        commandMatcher.match(transcript)?.let { match ->
            return RoutingDecision(
                target = RouteTarget.DETERMINISTIC,
                reason = "deterministic:${match.toolName}",
                timeoutMs = 3_000L,
                memorySharing = MemorySharing.NONE,
                sensitivity = context.sensitivity,
                fallback = RouteTarget.LOCAL,
            )
        }

        // A home-control intent is handled by Home Assistant when reachable, and
        // otherwise stays LOCAL — it must never be offloaded to the PC as if it
        // were a generic "complex" prompt.
        if (commandMatcher.looksLikeHomeAssistant(transcript)) {
            return if (context.homeAssistantReachable) {
                RoutingDecision(
                    target = RouteTarget.HOME_ASSISTANT,
                    reason = "home_assistant_intent",
                    timeoutMs = 8_000L,
                    memorySharing = MemorySharing.NONE,
                    sensitivity = context.sensitivity,
                    fallback = RouteTarget.LOCAL,
                )
            } else {
                RoutingDecision(
                    target = RouteTarget.LOCAL,
                    reason = "home_assistant_unreachable_local",
                    timeoutMs = localTimeoutMs,
                    memorySharing = MemorySharing.NONE,
                    sensitivity = context.sensitivity,
                    fallback = null,
                )
            }
        }

        val remoteAllowed = context.online &&
            context.pcReachable &&
            context.privacyProfile.allowsRemote &&
            context.sensitivity != SensitivityLevel.SENSITIVE &&
            context.complexity >= complexityThreshold

        if (remoteAllowed) {
            return RoutingDecision(
                target = RouteTarget.REMOTE_PC,
                reason = "complex_offloaded_to_pc",
                timeoutMs = remoteTimeoutMs,
                // Even when remote is allowed, memory defaults to selected fragments
                // only — never the whole vault (docs/PRIVACY.md §13/§14).
                memorySharing = if (context.touchesMemory) MemorySharing.SELECTED_FRAGMENTS_ONLY
                                else MemorySharing.NONE,
                sensitivity = context.sensitivity,
                fallback = RouteTarget.LOCAL,
            )
        }

        val reason = when {
            context.touchesMemory -> "memory_local_retrieval"
            !context.online -> "offline_local"
            context.privacyProfile == PrivacyProfile.DEVICE_ONLY -> "device_only_profile"
            else -> "simple_local"
        }
        return RoutingDecision(
            target = RouteTarget.LOCAL,
            reason = reason,
            timeoutMs = localTimeoutMs,
            memorySharing = MemorySharing.NONE,
            sensitivity = context.sensitivity,
            fallback = null,
        )
    }
}
