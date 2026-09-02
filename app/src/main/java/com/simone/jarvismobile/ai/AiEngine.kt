package com.simone.jarvismobile.ai

import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiFailureReason
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.snapshot.RelevantPersonalContext
import kotlinx.coroutines.flow.Flow

/**
 * One generation request, engine-agnostic — the same shape [LocalAiEngine]
 * and [RemoteAiEngine] both accept, so [AiRouter] can hand either engine the
 * same object regardless of which one the heuristic picked.
 *
 * [context] mirrors `JarvisCoreRequest.context` — only what the caller
 * explicitly puts here is ever sent onward (§ "non inviare automaticamente
 * al PC più dati di quelli necessari").
 */
data class AiRequest(
    val requestId: String,
    val text: String,
    val systemPrompt: String,
    val requestType: AiRequestType,
    val conversationId: String? = null,
    val context: Map<String, String> = emptyMap(),
    val timeoutSeconds: Long = 60,
    /** Set by [AiRouter] when its routing decision picked [AiExecutionTarget.REMOTE_BRAIN] — threaded to `JarvisCoreRequest.preferredModel`, ignored by [LocalAiEngine]. */
    val preferredModel: String? = null,
    /**
     * Already trimmed/budget-enforced/privacy-minimized (§ Personal
     * Intelligence Snapshot phase — `RelevantContextSelector`'s output).
     * Optional and additive: `null` behaves exactly as before this field
     * existed. [LocalAiEngine] renders it into the prompt text;
     * [RemoteAiEngine] renders it into `JarvisCoreRequest.context`.
     */
    val relevantContext: RelevantPersonalContext? = null,
)

/** Result of one [AiEngine.generate] call. */
data class AiEngineResult(
    val requestId: String,
    val success: Boolean,
    val text: String? = null,
    val target: AiExecutionTarget,
    val failureReason: AiFailureReason? = null,
    /** True only when this result is the product of a fallback after a remote failure (§ log label REMOTE_FAILED_FALLBACK_LOCAL). */
    val wasFallback: Boolean = false,
)

/** One streamed chunk — see [LocalAiEngine.stream] for why local streaming is honestly a single terminal chunk today. */
data class AiStreamChunk(
    val requestId: String,
    val delta: String = "",
    val done: Boolean = false,
    val error: AiFailureReason? = null,
)

/**
 * One AI backend behind [AiRouter] — [LocalAiEngine] (on-device, always
 * available) or [RemoteAiEngine] (JARVIS Core on the PC, optional). Nothing
 * outside `ai/`/`corebridge/` should ever hold a reference to a concrete
 * engine directly (§ richiesta esplicita: the rest of the app must not know
 * which model actually answered).
 */
interface AiEngine {
    val target: AiExecutionTarget
    suspend fun isAvailable(): Boolean
    suspend fun generate(request: AiRequest): AiEngineResult
    fun stream(request: AiRequest): Flow<AiStreamChunk>

    /** Best-effort cancellation of whatever this engine currently has in flight. */
    fun cancel(requestId: String)
}
