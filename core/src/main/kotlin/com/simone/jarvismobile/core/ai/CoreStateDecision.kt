package com.simone.jarvismobile.core.ai

/**
 * Pure decision behind [CoreConnectionManager]'s heartbeat (`app/corebridge/`),
 * extracted so it is unit-testable on a plain JVM — this repository has no
 * Robolectric/instrumented Android test infrastructure, so anything left
 * inline in a `Context`-dependent Android class can only be reviewed by eye,
 * never proven by a running test. Covers the "protocolVersion mismatch ->
 * local fallback" scenario explicitly required for the Core integration:
 * a mismatch never disables Core outright (a compatible-enough server could
 * still answer plenty of requests), it maps to [JarvisCoreState.DEGRADED] —
 * still [JarvisCoreState.remoteUsable], but a caller that actually sends a
 * request and gets a real protocol error back still falls back to local
 * inside that same call (see `AiRouter`'s fallback contract).
 */
object CoreStateDecision {
    fun fromHealthCheck(
        reachable: Boolean,
        protocolVersion: String?,
        expectedProtocolVersion: String,
        llmAvailable: Boolean?,
    ): JarvisCoreState = when {
        !reachable -> JarvisCoreState.OFFLINE
        protocolVersion != null && protocolVersion != expectedProtocolVersion -> JarvisCoreState.DEGRADED
        llmAvailable == false -> JarvisCoreState.DEGRADED
        else -> JarvisCoreState.ONLINE
    }
}
