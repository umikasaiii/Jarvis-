package com.simone.jarvismobile.ai

import android.util.Log
import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiFailureReason
import com.simone.jarvismobile.core.snapshot.RelevantContextRenderer
import com.simone.jarvismobile.llm.LlmRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the existing [LlmRouter] (today's only AI path) behind [AiEngine] —
 * preserves exact current behaviour, no rewrite of [LlmRouter] itself
 * (§ richiesta esplicita: "mantenere piena compatibilità con tutte le
 * funzioni correnti"). `requestType == COMPLEX/MEMORY` maps to
 * `needsReasoning = true` (the same signal [LlmRouter.selectSlot] already
 * uses to pick FAST vs ADVANCED), everything else stays on the fast slot.
 *
 * **Onestà su [stream]**: no token-level streaming exists anywhere in this
 * codebase today (confirmed by reading `LlmEngine`/`LitertLmEngine` — `chat()`
 * is a single suspend call that returns the complete answer). [stream] here
 * is therefore honestly a single terminal chunk carrying the whole reply,
 * not real incremental output — matching the same honesty already applied
 * to `BrainEvent`/`SentenceStream` in the Conversational engine (§ 6n).
 */
@Singleton
class LocalAiEngine @Inject constructor(
    private val llmRouter: LlmRouter,
) : AiEngine {

    override val target: AiExecutionTarget = AiExecutionTarget.LOCAL

    /** The local engine is always considered available — it is the app's offline-first guarantee, never gated on network. */
    override suspend fun isAvailable(): Boolean = true

    override suspend fun generate(request: AiRequest): AiEngineResult {
        val needsReasoning = request.requestType == com.simone.jarvismobile.core.ai.AiRequestType.COMPLEX ||
            request.requestType == com.simone.jarvismobile.core.ai.AiRequestType.MEMORY
        val contextBlock = request.relevantContext?.let { runCatching { RelevantContextRenderer.render(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
        val userText = if (contextBlock == null) request.text else "$contextBlock\n\n${request.text}"
        return try {
            val reply = llmRouter.chat(
                userText = userText,
                systemPrompt = request.systemPrompt,
                needsReasoning = needsReasoning,
                timeoutSeconds = request.timeoutSeconds,
            )
            if (reply == null) {
                AiEngineResult(request.requestId, success = false, target = target, failureReason = AiFailureReason.ENGINE_ERROR)
            } else {
                AiEngineResult(request.requestId, success = true, text = reply, target = target)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "local_generate_failed ${e.javaClass.simpleName}")
            AiEngineResult(request.requestId, success = false, target = target, failureReason = AiFailureReason.ENGINE_ERROR)
        }
    }

    override fun stream(request: AiRequest): Flow<AiStreamChunk> = flow {
        val result = generate(request)
        emit(
            AiStreamChunk(
                requestId = request.requestId,
                delta = result.text.orEmpty(),
                done = true,
                error = if (result.success) null else result.failureReason,
            ),
        )
    }

    /** [LlmRouter.cancel] is global (no per-request granularity exists today) — the same limit `SessionCoordinator.cancelTextGeneration` already lives with. */
    override fun cancel(requestId: String) {
        llmRouter.cancel()
    }

    private companion object {
        const val TAG = "LocalAiEngine"
    }
}
