package com.simone.jarvismobile.ai

import android.util.Log
import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiFailureReason
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.snapshot.RelevantContextRenderer
import com.simone.jarvismobile.corebridge.CoreClient
import com.simone.jarvismobile.corebridge.CoreExecutionTarget
import com.simone.jarvismobile.corebridge.CoreRequestType
import com.simone.jarvismobile.corebridge.CoreResponseStatus
import com.simone.jarvismobile.corebridge.JarvisCoreRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to JARVIS Core exclusively through [CoreClient] — never Retrofit/
 * OkHttp/WebSocket directly (§ richiesta esplicita architettura: UI/ViewModel
 * → Repository → [com.simone.jarvismobile.ai.AiRouter] → [RemoteAiEngine] →
 * [CoreClient]). [target] is fixed to [AiExecutionTarget.REMOTE_FAST] here —
 * [com.simone.jarvismobile.ai.AiRouter] is the one place that knows whether
 * the routing decision actually wanted the brain model, and threads that
 * through [AiRequest.context] as a hint Core itself resolves; this class
 * only ever reports back whichever target the caller told it to represent.
 */
@Singleton
class RemoteAiEngine @Inject constructor(
    private val coreClient: CoreClient,
) : AiEngine {

    override val target: AiExecutionTarget = AiExecutionTarget.REMOTE_FAST

    override suspend fun isAvailable(): Boolean =
        runCatching { coreClient.healthCheck().reachable }.getOrDefault(false)

    override suspend fun generate(request: AiRequest): AiEngineResult {
        val coreRequest = JarvisCoreRequest(
            requestId = request.requestId,
            conversationId = request.conversationId,
            requestType = request.requestType.toCoreType(),
            text = request.text,
            context = contextMapFor(request),
            preferredTarget = request.toCoreExecutionTarget(),
            allowFallback = true,
            systemPrompt = request.systemPrompt.takeIf { it.isNotBlank() },
        )
        return try {
            val response = withTimeout(request.timeoutSeconds * 1000) { coreClient.send(coreRequest) }
            when (response.status) {
                CoreResponseStatus.OK, CoreResponseStatus.PARTIAL ->
                    AiEngineResult(request.requestId, success = true, text = response.text, target = target)
                CoreResponseStatus.ERROR ->
                    AiEngineResult(request.requestId, success = false, target = target, failureReason = AiFailureReason.ENGINE_ERROR)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "remote_generate_timeout")
            AiEngineResult(request.requestId, success = false, target = target, failureReason = AiFailureReason.TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "remote_generate_network_failed ${e.javaClass.simpleName}")
            AiEngineResult(request.requestId, success = false, target = target, failureReason = AiFailureReason.NETWORK)
        } catch (e: Exception) {
            Log.w(TAG, "remote_generate_failed ${e.javaClass.simpleName}")
            AiEngineResult(request.requestId, success = false, target = target, failureReason = AiFailureReason.NETWORK)
        }
    }

    override fun stream(request: AiRequest): Flow<AiStreamChunk> {
        val coreRequest = JarvisCoreRequest(
            requestId = request.requestId,
            conversationId = request.conversationId,
            requestType = request.requestType.toCoreType(),
            text = request.text,
            context = contextMapFor(request),
            preferredTarget = request.toCoreExecutionTarget(),
            allowFallback = true,
            systemPrompt = request.systemPrompt.takeIf { it.isNotBlank() },
        )
        return coreClient.stream(coreRequest)
            .map { chunk ->
                AiStreamChunk(
                    requestId = chunk.requestId,
                    delta = chunk.delta,
                    done = chunk.done,
                    error = if (chunk.error != null) AiFailureReason.NETWORK else null,
                )
            }
            .catch { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "remote_stream_failed ${e.javaClass.simpleName}")
                emit(AiStreamChunk(request.requestId, done = true, error = AiFailureReason.NETWORK))
            }
    }

    override fun cancel(requestId: String) {
        coreClient.cancel(requestId)
    }

    /**
     * Merges the caller's own [AiRequest.context] with a minimized snapshot
     * projection (§ richiesta esplicita: "NON inviare automaticamente Raw
     * Snapshot completo... non inviare più dati di quelli necessari") — no
     * protocol change: `JarvisCoreRequest.context: Map<String, String>`
     * already existed for exactly this purpose. Snapshot-derived keys are
     * prefixed to never silently overwrite a caller-supplied key.
     */
    private fun contextMapFor(request: AiRequest): Map<String, String> {
        val fromSnapshot = request.relevantContext?.let { runCatching { RelevantContextRenderer.renderForCore(it) }.getOrNull() }.orEmpty()
        if (fromSnapshot.isEmpty()) return request.context
        return request.context + fromSnapshot.mapKeys { (k, _) -> "snapshot_$k" }
    }

    /**
     * [AiRouter] only ever calls [RemoteAiEngine] once it has already decided
     * REMOTE_FAST or REMOTE_BRAIN — it threads which one through
     * [AiRequest.preferredModel] (`"brain"` or null, see
     * [AiRouter.withPreferredModel]). Maps that into the real wire
     * `preferredTarget` (jarvis-protocol/main) so Core actually honors the
     * client-side routing decision instead of silently re-deciding via AUTO.
     */
    private fun AiRequest.toCoreExecutionTarget(): CoreExecutionTarget =
        if (preferredModel == "brain") CoreExecutionTarget.BRAIN else CoreExecutionTarget.FAST

    private fun AiRequestType.toCoreType(): CoreRequestType = when (this) {
        AiRequestType.COMMAND -> CoreRequestType.COMMAND
        AiRequestType.CHAT -> CoreRequestType.CHAT
        AiRequestType.COMPLEX -> CoreRequestType.COMPLEX
        AiRequestType.MEMORY -> CoreRequestType.MEMORY
        AiRequestType.TOOL -> CoreRequestType.TOOL
        AiRequestType.PROACTIVE -> CoreRequestType.PROACTIVE
    }

    private companion object {
        const val TAG = "RemoteAiEngine"
    }
}
