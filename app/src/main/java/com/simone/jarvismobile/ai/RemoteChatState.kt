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
}
