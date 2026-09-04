package com.simone.jarvismobile.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared, engine-agnostic state for a JARVIS Core chat attempt — extracted
 * out of `SessionCoordinator` so both `SessionCoordinator.tryRemoteChat`
 * (Motore Classico) and `JarvisBrain.tryRemoteReply` (Motore Conversazionale)
 * track and cancel the SAME in-flight remote call through the SAME single
 * [RemoteAiEngine], instead of each keeping its own private bookkeeping
 * (§ "NON creare un secondo router/client/pipeline" — this is what makes
 * that true for cancellation/diagnostics too, not just for the call itself).
 *
 * At most one of the two callers is ever mid-turn at a time (one turn, one
 * engine chosen by `JarvisEngineRouter` up front), so a single shared slot
 * is correct — never two concurrent remote chat attempts to coordinate.
 */
@Singleton
class RemoteChatState @Inject constructor() {

    /**
     * The id of a remote (JARVIS Core) chat call currently in flight, if any.
     * Plain coroutine cancellation already stops the suspend call, but
     * `SessionCoordinator.cancelTextGeneration` also needs to close the
     * underlying HTTP/SSE connection explicitly, which only
     * [RemoteAiEngine.cancel] does — this is what it reads to find the id.
     */
    @Volatile var activeRequestId: String? = null

    /**
     * "Chi ha risposto davvero all'ultimo messaggio?" (§ segnalazione utente:
     * "si collega con Core ma non usa il modello AI") — `CORE FAST`/`CORE
     * BRAIN`, or `LOCAL (motivo)`. Written by whichever of the two callers
     * above actually ran this turn; surfaced read-only in Diagnostica.
     */
    private val _lastRoute = MutableStateFlow<String?>(null)
    val lastRoute: StateFlow<String?> = _lastRoute.asStateFlow()

    fun setLastRoute(value: String) {
        _lastRoute.value = value
    }

    /**
     * Written **only** by [recordAttempt], called from the same two call
     * sites as [setLastRoute] above ([com.simone.jarvismobile.engine.JarvisBrain.tryRemoteReply]
     * and `SessionCoordinator.tryRemoteChat`) — a structured counterpart to
     * [lastRoute]'s human-readable string, for the exact fields requested by
     * the "tryRemoteReply non arriva alla chiamata HTTP" audit: which engine
     * ran, what the routing decision actually was and why, the raw toggles
     * it was computed from, and whether a remote call was even attempted
     * before falling back. `null` means no chat turn has run this session —
     * distinct from [RemoteAttemptOutcome.NOT_ATTEMPTED], which means a turn
     * ran but routing chose LOCAL before ever reaching [RemoteAiEngine].
     */
    private val _lastAttempt = MutableStateFlow<LastRemoteAttempt?>(null)
    val lastAttempt: StateFlow<LastRemoteAttempt?> = _lastAttempt.asStateFlow()

    fun recordAttempt(attempt: LastRemoteAttempt) {
        _lastAttempt.value = attempt
    }
}

/** See [RemoteChatState.lastAttempt]. */
enum class RemoteAttemptOutcome { NOT_ATTEMPTED, STARTED, SUCCESS, FAILED }

/** See [RemoteChatState.lastAttempt]. */
data class LastRemoteAttempt(
    val engine: String,
    val requestType: String,
    val target: String,
    val reason: String,
    val coreState: String,
    val coreEnabled: Boolean,
    val remoteAiEnabled: Boolean,
    val preferredRemote: Boolean,
    val outcome: RemoteAttemptOutcome,
    val failureReason: String? = null,
    val endpoint: String? = null,
    val atMs: Long = System.currentTimeMillis(),
)
