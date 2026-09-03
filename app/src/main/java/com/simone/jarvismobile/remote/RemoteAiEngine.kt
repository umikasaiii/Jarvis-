package com.simone.jarvismobile.remote

import android.util.Log
import com.simone.jarvismobile.core.remote.AiTarget
import com.simone.jarvismobile.core.remote.CoreResult
import com.simone.jarvismobile.core.remote.ExecutionTarget
import com.simone.jarvismobile.core.remote.JarvisRequest
import com.simone.jarvismobile.core.remote.StreamEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RemoteChatOutcome {
    data class Answered(
        val text: String,
        val modelUsed: String?,
        val targetUsed: ExecutionTarget?,
    ) : RemoteChatOutcome

    /** [reason] is a technical code only (docs/SECURITY.md log-redaction rule) — never echoes user text. */
    data class Failed(val reason: String) : RemoteChatOutcome
}

/**
 * Turns one Android conversational turn into a jarvis-protocol v1 exchange
 * with JARVIS Core and back. Talks to Core exclusively through
 * [com.simone.jarvismobile.core.remote.JarvisCoreClient] — the wire shape is
 * entirely jarvis-protocol/main's; nothing here invents a field or endpoint.
 *
 * Uses POST /v1/ai/stream internally (real streaming — task §7) but exposes a
 * single suspend function returning the assembled final text, matching
 * [com.simone.jarvismobile.llm.LlmEngine.chat]'s shape exactly so the caller
 * (SessionCoordinator.chatReply) can slot a remote answer into the SAME
 * single-final-reply pipeline the local engine already uses, instead of
 * standing up a second, parallel streaming chat surface in the UI.
 */
@Singleton
class RemoteAiEngine @Inject constructor(
    private val connection: CoreConnectionRepository,
) {
    @Volatile private var activeJob: Job? = null

    suspend fun chat(text: String, target: AiTarget, conversationId: String? = null): RemoteChatOutcome {
        val client = connection.clientOrNull() ?: return RemoteChatOutcome.Failed("core_disabled")
        val preferredTarget = when (target) {
            AiTarget.REMOTE_FAST -> ExecutionTarget.FAST
            AiTarget.REMOTE_BRAIN -> ExecutionTarget.BRAIN
            AiTarget.LOCAL -> ExecutionTarget.AUTO // not a valid caller state; treated as AUTO defensively
        }
        val request = JarvisRequest(text = text, preferredTarget = preferredTarget, conversationId = conversationId)

        val builder = StringBuilder()
        var modelUsed: String? = null
        var targetUsed: ExecutionTarget? = null
        var failureReason: String? = null

        return try {
            coroutineScope {
                val job = launch {
                    client.stream(request).collect { result ->
                        when (result) {
                            is CoreResult.Success -> {
                                val event = result.value
                                when (event.type) {
                                    StreamEventType.START -> targetUsed = event.targetUsed
                                    StreamEventType.TOKEN -> event.content?.let(builder::append)
                                    StreamEventType.DONE -> {
                                        modelUsed = event.modelUsed
                                        targetUsed = event.targetUsed ?: targetUsed
                                    }
                                    StreamEventType.ERROR -> failureReason = event.error ?: "remote_error"
                                }
                            }
                            is CoreResult.Failure -> failureReason = describe(result)
                        }
                    }
                }
                activeJob = job
                job.join()
            }
            activeJob = null

            when {
                failureReason != null -> {
                    Log.w(TAG, "remote_chat_failed code=$failureReason")
                    connection.reportRuntimeFailure()
                    RemoteChatOutcome.Failed(failureReason!!)
                }
                builder.isEmpty() -> {
                    connection.reportRuntimeFailure()
                    RemoteChatOutcome.Failed("empty_remote_reply")
                }
                else -> RemoteChatOutcome.Answered(builder.toString(), modelUsed, targetUsed)
            }
        } catch (e: CancellationException) {
            activeJob = null
            throw e
        }
    }

    /** Closes the underlying SSE connection immediately — no inference left running client-side. */
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    private fun describe(failure: CoreResult.Failure): String = when (failure) {
        is CoreResult.Failure.Http -> "http_${failure.code}_${failure.errorCode ?: "error"}"
        is CoreResult.Failure.ProtocolMismatch -> "protocol_mismatch"
        is CoreResult.Failure.Network -> "network_unreachable"
        is CoreResult.Failure.Timeout -> "timeout"
        is CoreResult.Failure.Malformed -> "malformed_response"
        CoreResult.Failure.Cancelled -> "cancelled"
    }

    private companion object {
        const val TAG = "RemoteAiEngine"
    }
}
