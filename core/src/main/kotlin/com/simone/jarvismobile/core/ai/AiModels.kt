package com.simone.jarvismobile.core.ai

/**
 * Pure data shapes for the AI Router / Core Client / Event Bridge infrastructure
 * (JARVIS Core PC companion, phase "fondamenta"). See `app/ai/AiRouter.kt` for
 * the actual orchestration — everything here is deliberately free of Android
 * dependencies so the routing decision itself stays unit-testable in this module.
 *
 * **Onestà, per non duplicare uno scaffold già esistente**: `core/routing/`
 * (`AssistantRouter`/`HybridRouter`/`RoutingDecision`/`PrivacyProfile`) is an
 * earlier, broader turn-level router built in an early phase and never wired
 * into the live app (`RouteTarget.LOCAL` is still hardcoded in
 * `SessionCoordinator`, confirmed by grep — no other file references
 * `HybridRouter`). It answers "what handles this whole turn" (deterministic
 * command / Home Assistant / local / remote PC). [AiExecutionTarget] below is
 * a narrower, different-layer concept — specifically which AI backend serves
 * one generation call, sitting where `llm.ModelSlot` (FAST/ADVANCED) already
 * sits, now split further into a local/remote axis — so it is new, not a
 * duplicate, and this phase deliberately leaves `core/routing/` untouched
 * rather than rewriting it. Privacy/sensitivity concepts, however, ARE reused
 * directly: [com.simone.jarvismobile.core.tools.SensitivityLevel] doubles as
 * the event privacy level Event Bridge needs, instead of a second
 * near-identical enum.
 */

/** Which AI backend should actually answer a generation call. */
enum class AiExecutionTarget {
    /** The on-device model (today's only behaviour) — always available, no network. */
    LOCAL,

    /** JARVIS Core on the companion PC, its own fast/default model. */
    REMOTE_FAST,

    /** JARVIS Core on the companion PC, its larger/"brain" model for hard requests. */
    REMOTE_BRAIN,
}

/**
 * What kind of request is being routed. Coarse on purpose (§ richiesta esplicita:
 * "NON creare classificatori inutilmente complessi... euristiche semplici").
 */
enum class AiRequestType {
    /** A deterministic command JARVIS could likely resolve without any model at all. */
    COMMAND,

    /** Ordinary conversational chat. */
    CHAT,

    /** Reasoning, planning, multi-step analysis — wants the bigger brain when one exists. */
    COMPLEX,

    /** Retrieval-augmented / advanced memory requests. */
    MEMORY,

    /** A tool-call round already in flight (JarvisBrain's tool loop). */
    TOOL,

    /** Proactive/background generation (briefings, suggestions) — never urgent, never blocking a live turn. */
    PROACTIVE,
}

/** Why a generation attempt failed — drives whether [AiExecutionTarget.LOCAL] fallback is attempted. */
enum class AiFailureReason {
    /** The remote call exceeded its deadline. Recoverable — always falls back. */
    TIMEOUT,

    /** No route to JARVIS Core (unreachable, connection refused, DNS, etc). Recoverable — always falls back. */
    NETWORK,

    /** The caller cancelled the request. Never falls back — cancellation means "stop", not "retry elsewhere". */
    CANCELLED,

    /** JARVIS Core responded with a real error (bad request, server error). Recoverable — falls back. */
    ENGINE_ERROR,

    /** The engine is known unavailable before even trying (Core disabled/offline). Recoverable — falls back. */
    UNAVAILABLE,
}

/**
 * Observable connection state toward JARVIS Core — the PC-side companion
 * server. [DISABLED] is the default until the user configures and turns it
 * on (§ richiesta esplicita: "Core disattivato finché non configurato").
 */
enum class JarvisCoreState {
    /** The user has not enabled JARVIS Core. No connection is ever attempted. */
    DISABLED,

    /** Enabled, and a health check is currently in flight for the first time (or after a state change). */
    CONNECTING,

    /** Reachable and healthy. */
    ONLINE,

    /** Reachable but reporting a partial/degraded state (e.g. some capability unavailable) — still usable, cautiously. */
    DEGRADED,

    /** Enabled but the last health check failed to reach it (network/timeout). */
    OFFLINE,

    /** Enabled and reachable, but responded with a real error condition. */
    ERROR,
    ;

    /** Whether it currently makes sense to even attempt a remote request. */
    val remoteUsable: Boolean get() = this == ONLINE || this == DEGRADED
}
