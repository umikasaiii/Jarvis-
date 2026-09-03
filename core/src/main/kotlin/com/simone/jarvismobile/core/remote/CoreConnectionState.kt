package com.simone.jarvismobile.core.remote

/**
 * Centralized Core reachability state (task: "Introduci uno stato
 * centralizzato"). Android polls this passively/on-demand (see
 * CoreConnectionRepository in app/) — never aggressively.
 */
enum class CoreConnectionState {
    /** User has not enabled JARVIS Core. Nothing is ever attempted. */
    DISABLED,

    /** A health check is currently in flight. */
    CONNECTING,

    /** Last health check succeeded and the protocol version matched. */
    ONLINE,

    /** Reachable, but something is off: wrong protocolVersion or llmAvailable=false. */
    DEGRADED,

    /** Last attempt could not reach Core at all (network/timeout/refused). */
    OFFLINE,

    /** Core answered but with something this client could not make sense of. */
    ERROR,
}

/** One evaluated health probe, used both to derive [CoreConnectionState] and to show the user something concrete. */
data class CoreConnectionCheck(
    val state: CoreConnectionState,
    val reachable: Boolean,
    val latencyMs: Long?,
    val serverVersion: String? = null,
    val protocolVersion: String? = null,
    val protocolMatches: Boolean = false,
    val capabilities: CapabilitiesResponse? = null,
    val error: String? = null,
)

/** Pure classification: given a client result (and how long it took), what state does it mean? Fully unit-testable. */
object CoreConnectionClassifier {

    fun classify(
        healthResult: CoreResult<HealthResponse>,
        latencyMs: Long,
        capabilities: CapabilitiesResponse? = null,
    ): CoreConnectionCheck = when (healthResult) {
        is CoreResult.Success -> {
            val health = healthResult.value
            val protocolMatches = health.protocolVersion == JARVIS_PROTOCOL_VERSION
            val state = when {
                !protocolMatches -> CoreConnectionState.DEGRADED
                !health.llmAvailable -> CoreConnectionState.DEGRADED
                else -> CoreConnectionState.ONLINE
            }
            CoreConnectionCheck(
                state = state,
                reachable = true,
                latencyMs = latencyMs,
                serverVersion = health.serverVersion,
                protocolVersion = health.protocolVersion,
                protocolMatches = protocolMatches,
                capabilities = capabilities,
            )
        }

        is CoreResult.Failure.Network, is CoreResult.Failure.Timeout ->
            CoreConnectionCheck(
                state = CoreConnectionState.OFFLINE,
                reachable = false,
                latencyMs = null,
                error = (healthResult as CoreResult.Failure).let(::messageOf),
            )

        is CoreResult.Failure.Cancelled ->
            CoreConnectionCheck(state = CoreConnectionState.OFFLINE, reachable = false, latencyMs = null, error = "cancelled")

        is CoreResult.Failure ->
            CoreConnectionCheck(
                state = CoreConnectionState.ERROR,
                reachable = false,
                latencyMs = latencyMs,
                error = messageOf(healthResult),
            )
    }

    private fun messageOf(failure: CoreResult.Failure): String = when (failure) {
        is CoreResult.Failure.Http -> failure.message
        is CoreResult.Failure.ProtocolMismatch -> failure.message
        is CoreResult.Failure.Network -> failure.message
        is CoreResult.Failure.Timeout -> failure.message
        is CoreResult.Failure.Malformed -> failure.message
        CoreResult.Failure.Cancelled -> "cancelled"
    }
}
