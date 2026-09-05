package com.simone.jarvismobile.ai

import android.util.Log
import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.ai.AiRouteDecision
import com.simone.jarvismobile.core.ai.AiRoutingHeuristic
import com.simone.jarvismobile.core.ai.AiRoutingPreferences
import com.simone.jarvismobile.core.snapshot.RelevantContextSelector
import com.simone.jarvismobile.snapshot.PersonalIntelligenceSnapshotCache
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
 * hold a reference to [LocalAiEngine]/[RemoteAiEngine] directly.
 *
 * § FASE 2A.8 update: `JarvisBrain.tryRemoteReply`/`SessionCoordinator.tryRemoteChat`
 * used to each reimplement this class' own "decide → call remote → classify"
 * step independently (three structurally-identical copies, confirmed by
 * audit) instead of calling through here — [attemptRemoteOnly] is the shared
 * entry point that ends that duplication for the REMOTE leg specifically.
 * Their LOCAL fallback still deliberately does NOT go through [generate]'s
 * own [LocalAiEngine] (see [attemptRemoteOnly]'s doc comment for why — it
 * would reintroduce a cross-turn contamination bug fixed elsewhere), so each
 * keeps calling `LlmRouter` directly for that one piece only.
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
    private val snapshotCache: PersonalIntelligenceSnapshotCache,
    private val snapshotGate: SnapshotContextGate,
) {
    /** requestId -> which engine actually owns the in-flight call, so [cancel] can target the right one. */
    private val inFlightTarget = ConcurrentHashMap<String, AiExecutionTarget>()

    suspend fun generate(request: AiRequest): AiEngineResult {
        val enriched = withAutoContext(request)
        val decision = decide(enriched.requestType)
        return when (decision.target) {
            AiExecutionTarget.LOCAL -> runLocal(enriched)
            AiExecutionTarget.REMOTE_FAST, AiExecutionTarget.REMOTE_BRAIN -> runRemoteWithFallback(enriched, decision)
        }
    }

    /**
     * The "UserRequest → SnapshotBuilder → Selector → AiRouter" step of the
     * requested architecture, automatic and additive: a caller that already
     * attached [AiRequest.relevantContext] is left untouched; otherwise this
     * builds one from the cached [PersonalIntelligenceSnapshotCache] +
     * [RelevantContextSelector] — never blocking the turn if that fails (§
     * richiesta esplicita: "Se la costruzione del contesto fallisce:
     * continuare normalmente senza snapshot").
     */
    private suspend fun withAutoContext(request: AiRequest): AiRequest {
        if (request.relevantContext != null) return request
        if (!snapshotGate.enabled()) return request
        val relevant = runCatching {
            val snapshot = snapshotCache.get()
            RelevantContextSelector.select(snapshot, request.requestType, request.text, budget = snapshotGate.budget())
        }.getOrNull()
        return if (relevant == null) request else request.copy(relevantContext = relevant)
    }

    /**
     * Streaming variant of [generate]. Falls back the same way — remote
     * chunks are buffered (not emitted live) until the remote stream
     * completes, so the decision "replay them" vs "fall back to local" can
     * be made once, correctly: a stream that fails outright (a single bare
     * error chunk, no real delta) falls back exactly like [generate] does,
     * while any real content already produced is always replayed in full,
     * never silently discarded and replaced by the local engine's answer —
     * the one thing the fallback contract explicitly forbids.
     */
    fun stream(request: AiRequest): Flow<AiStreamChunk> = flow {
        val request = withAutoContext(request)
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
        val remoteRequest = request.withPreferredModel(decision.target)
        // Buffered rather than emitted live: a chunk that is itself a bare
        // terminal error (no delta) must not count as "content reached the
        // caller" — otherwise a stream that fails outright would relay that
        // one error chunk and skip the local fallback entirely, breaking the
        // same guarantee generate() gives. Buffering also means this stream
        // is not truly incremental for the remote path today — an accepted
        // trade-off given neither engine offers real token-by-token delivery
        // yet (§ onestà, see LocalAiEngine's own doc comment).
        val buffered = mutableListOf<AiStreamChunk>()
        var hasRealContent = false
        try {
            remote.stream(remoteRequest).collect { chunk ->
                buffered += chunk
                if (chunk.delta.isNotEmpty() || (chunk.done && chunk.error == null)) hasRealContent = true
            }
        } catch (e: CancellationException) {
            inFlightTarget.remove(request.requestId)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "remote_stream_exception ${e.javaClass.simpleName}")
        }
        if (hasRealContent) {
            buffered.forEach { emit(it) }
        } else {
            Log.i(TAG, "ai_route target=REMOTE_FAILED_FALLBACK_LOCAL type=${request.requestType}")
            inFlightTarget[request.requestId] = AiExecutionTarget.LOCAL
            local.stream(request).collect { emit(it) }
        }
        inFlightTarget.remove(request.requestId)
    }

    /**
     * § FASE 2A.8 — the shared "decide → call the remote engine only →
     * classify the outcome" step, extracted so [JarvisBrain][com.simone.jarvismobile.engine.JarvisBrain]/
     * `SessionCoordinator` stop each independently reimplementing it (audit
     * confirmed: both had their own copy, structurally identical to
     * [runRemoteWithFallback] below, down to the same `LastRemoteAttempt`
     * shape). Deliberately does NOT fall back to [local] itself — those two
     * callers each need their OWN local fallback (Classico's `chatReply`'s
     * cached/retried `router.chat(...)`, Conversazionale's turn-isolated
     * `chatStateless(...)`), never [LocalAiEngine]'s single-shot persistent-
     * conversation `generate()`, which would reintroduce the exact cross-turn
     * contamination FASE 2A.6 §9 fixed if either of them routed their local
     * fallback through here instead. [generate]/[stream] below keep their own
     * existing internal remote-then-[local]-fallback logic unchanged (lower
     * risk than rewiring an already-tested method to share this), so this is
     * an ADDITIONAL entry point, not a replacement for them.
     *
     * [request]'s own `timeoutSeconds` is never used for the remote leg — see
     * [REMOTE_FAST_TIMEOUT_SECONDS]'s doc comment for why a caller's local-
     * generation budget must never leak into how long a network call is
     * allowed to hang before falling back.
     */
    suspend fun attemptRemoteOnly(request: AiRequest): RemoteAttempt {
        val prefs = routingContext.preferencesFor(request.requestType)
        val decision = AiRoutingHeuristic.decide(request.requestType, prefs)
        if (decision.target == AiExecutionTarget.LOCAL) {
            return RemoteAttempt(decision, prefs, RemoteAttemptOutcome.NOT_ATTEMPTED)
        }
        inFlightTarget[request.requestId] = decision.target
        val remoteTimeoutSeconds = if (decision.target == AiExecutionTarget.REMOTE_BRAIN) {
            REMOTE_BRAIN_TIMEOUT_SECONDS
        } else {
            REMOTE_FAST_TIMEOUT_SECONDS
        }
        val remoteRequest = request.withPreferredModel(decision.target).copy(timeoutSeconds = remoteTimeoutSeconds)
        val result = try {
            remote.generate(remoteRequest)
        } catch (e: CancellationException) {
            inFlightTarget.remove(request.requestId)
            throw e
        }
        inFlightTarget.remove(request.requestId)
        val text = result.text?.takeIf { result.success && it.isNotBlank() }
        if (text != null) return RemoteAttempt(decision, prefs, RemoteAttemptOutcome.SUCCESS, text = text)
        val reason = if (!result.success) {
            (result.failureReason?.name ?: "unknown") + (result.errorDetail?.let { ": $it" } ?: "")
        } else {
            "empty_reply"
        }
        return RemoteAttempt(decision, prefs, RemoteAttemptOutcome.FAILED, failureReason = reason)
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
        // § FASE 2A.8 — see REMOTE_FAST_TIMEOUT_SECONDS's doc comment: never
        // the caller's own (local-generation-sized) request.timeoutSeconds.
        val remoteTimeoutSeconds = if (decision.target == AiExecutionTarget.REMOTE_BRAIN) {
            REMOTE_BRAIN_TIMEOUT_SECONDS
        } else {
            REMOTE_FAST_TIMEOUT_SECONDS
        }
        val remoteRequest = request.withPreferredModel(decision.target).copy(timeoutSeconds = remoteTimeoutSeconds)
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

/**
 * The full outcome of one [AiRouter.attemptRemoteOnly] call — everything a
 * caller needs to build its own `LastRemoteAttempt` diagnostics record (the
 * two raw `SettingsRepository` toggles `coreEnabled`/`corePreferRemote` stay
 * the caller's own responsibility, exactly as before, since [AiRouter] itself
 * has no reason to depend on `SettingsRepository` beyond what
 * [AiRoutingPreferences] already exposes).
 */
data class RemoteAttempt(
    val decision: AiRouteDecision,
    val prefs: AiRoutingPreferences,
    val outcome: RemoteAttemptOutcome,
    val text: String? = null,
    val failureReason: String? = null,
)
