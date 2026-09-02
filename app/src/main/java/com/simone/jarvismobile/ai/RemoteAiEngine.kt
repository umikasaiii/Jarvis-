package com.simone.jarvismobile.ai

import android.util.Log
import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiFailureReason
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.corebridge.CoreClient
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
            timestamp = System.currentTimeMillis(),
            requestType = request.requestType.toCoreType(),
            text = request.text,
            context = request.context,
            preferredModel = request.preferredModel,
            allowFallback = true,
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
            timestamp = System.currentTimeMillis(),
            requestType = request.requestType.toCoreType(),
            text = request.text,
            context = request.context,
            preferredModel = request.preferredModel,
            allowFallback = true,
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
