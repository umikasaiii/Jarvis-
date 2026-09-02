package com.simone.jarvismobile.ai

import android.util.Log
import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.ai.AiRouteDecision
import com.simone.jarvismobile.core.ai.AiRoutingHeuristic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point the rest of the app should call for any AI
 * generation from here on (§ richiesta esplicita: "il resto dell'app non
 * deve sapere quale modello risponde"). Nothing outside `ai/` is expected to
 * hold a reference to [LocalAiEngine]/[RemoteAiEngine] directly — today
 * nothing does yet, since this phase deliberately does not rewire
 * `SessionCoordinator`/`ConversationalJarvisEngine`/`JarvisBrain` to call
 * through here (§ onestà: see CLAUDE.md phase note — those keep calling
 * `LlmRouter` directly, unchanged, until a future phase wires them through
 * [AiRouter]).
 *
 * **Fallback contract (§ richiesta esplicita, la parte più delicata)**: a
 * remote attempt that fails for any recoverable reason (timeout, network,
 * engine error, or the engine being unavailable before even trying) falls
 * back to [LocalAiEngine] **inside the same [generate] call**, before ever
 * returning to the caller — so there is exactly one [AiEngineResult] per
 * request, never two, and the caller never sees a half-finished remote
 * attempt. A [kotlinx.coroutines.CancellationException] is the one case that
 * never falls back — the caller asked to stop, not "try somewhere else"
 * (matches [com.simone.jarvismobile.core.ai.AiFailureReason.CANCELLED]).
 */
@Singleton
class AiRouter @Inject constructor(
    @LocalEngine private val local: AiEngine,
    @RemoteEngine private val remote: AiEngine,
    private val routingContext: AiRoutingContextProvider,
) {
    /** requestId -> which engine actually owns the in-flight call, so [cancel] can target the right one. */
    private val inFlightTarget = ConcurrentHashMap<String, AiExecutionTarget>()

    suspend fun generate(request: AiRequest): AiEngineResult {
        val decision = decide(request.requestType)
        return when (decision.target) {
            AiExecutionTarget.LOCAL -> runLocal(request)
            AiExecutionTarget.REMOTE_FAST, AiExecutionTarget.REMOTE_BRAIN -> runRemoteWithFallback(request, decision)
        }
    }

    /**
     * Streaming variant of [generate]. Falls back the same way — but since a
     * stream may already have emitted partial chunks before failing, the
     * fallback is only attempted when **zero** chunks were emitted yet (a
     * partial remote answer is never silently discarded and replaced, which
     * would look like the local engine "correcting" the remote one and could
     * read as a double/contradictory response — the one thing the fallback
     * contract explicitly forbids).
     */
    fun stream(request: AiRequest): Flow<AiStreamChunk> = flow {
        val decision = decide(request.requestType)
        if (decision.target == AiExecutionTarget.LOCAL) {
            Log.i(TAG, "ai_route target=LOCAL type=${request.requestType} reason=${decision.reason}")
            inFlightTarget[request.requestId] = AiExecutionTarget.LOCAL
            local.stream(request).collect { emit(it) }
            inFlightTarget.remove(request.requestId)
            return@flow
        }
        Log.i(TAG, "ai_route target=${decision.target} type=${request.requestType} reason=${decision.reason}")
        inFlightTarget[request.requestId] = decision.target
        var emittedAny = false
        val remoteRequest = request.withPreferredModel(decision.target)
        runCatching {
            remote.stream(remoteRequest).collect { chunk ->
                emittedAny = true
                emit(chunk)
            }
        }.onFailure { e ->
            if (e is CancellationException) { inFlightTarget.remove(request.requestId); throw e }
        }
        // Only ever falls back when nothing reached the caller yet — a partial
        // remote stream is never discarded and replaced (§ doc comment above).
        if (!emittedAny) {
            Log.i(TAG, "ai_route target=REMOTE_FAILED_FALLBACK_LOCAL type=${request.requestType}")
            inFlightTarget[request.requestId] = AiExecutionTarget.LOCAL
            local.stream(request).collect { emit(it) }
        }
        inFlightTarget.remove(request.requestId)
    }

    fun cancel(requestId: String) {
        when (inFlightTarget[requestId]) {
            AiExecutionTarget.LOCAL -> local.cancel(requestId)
            AiExecutionTarget.REMOTE_FAST, AiExecutionTarget.REMOTE_BRAIN -> remote.cancel(requestId)
            null -> { local.cancel(requestId); remote.cancel(requestId) }
        }
        inFlightTarget.remove(requestId)
    }

    private suspend fun runLocal(request: AiRequest): AiEngineResult {
        Log.i(TAG, "ai_route target=LOCAL type=${request.requestType}")
        inFlightTarget[request.requestId] = AiExecutionTarget.LOCAL
        val result = local.generate(request)
        inFlightTarget.remove(request.requestId)
        return result
    }

    private suspend fun runRemoteWithFallback(request: AiRequest, decision: AiRouteDecision): AiEngineResult {
        Log.i(TAG, "ai_route target=${decision.target} type=${request.requestType} reason=${decision.reason}")
        inFlightTarget[request.requestId] = decision.target
        val remoteRequest = request.withPreferredModel(decision.target)
        val remoteResult = try {
            remote.generate(remoteRequest)
        } catch (e: CancellationException) {
            inFlightTarget.remove(request.requestId)
            throw e
        }
        if (remoteResult.success) {
            inFlightTarget.remove(request.requestId)
            return remoteResult.copy(target = decision.target)
        }
        // Recoverable failure (timeout/network/engine error/unavailable) — fall back inside this same call.
        Log.i(TAG, "ai_route target=REMOTE_FAILED_FALLBACK_LOCAL type=${request.requestType} cause=${remoteResult.failureReason}")
        inFlightTarget[request.requestId] = AiExecutionTarget.LOCAL
        val localResult = local.generate(request)
        inFlightTarget.remove(request.requestId)
        return localResult.copy(wasFallback = true)
    }

    private fun AiRequest.withPreferredModel(target: AiExecutionTarget): AiRequest =
        if (target == AiExecutionTarget.REMOTE_BRAIN) copy(preferredModel = "brain") else this

    private suspend fun decide(requestType: AiRequestType): AiRouteDecision =
        AiRoutingHeuristic.decide(requestType, routingContext.preferencesFor(requestType))

    private companion object {
        const val TAG = "AiRouter"
    }
}
