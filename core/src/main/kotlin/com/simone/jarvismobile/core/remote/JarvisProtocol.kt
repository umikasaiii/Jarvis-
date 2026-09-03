package com.simone.jarvismobile.core.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for JARVIS Core (the optional PC companion), matching
 * jarvis-protocol/main (v1.0.0, protocolVersion "1") field-for-field:
 * https://github.com/umikasaiii/Jarvis-protocol — schemas/jarvis-request.schema.json,
 * jarvis-response.schema.json, stream-event.schema.json, health.schema.json,
 * capabilities.schema.json, common.schema.json.
 *
 * jarvis-protocol/main is immutable from this repository: nothing here may add
 * a field, endpoint or enum value the protocol does not already declare. If a
 * real need shows up, it is a protocol change proposal, not a local variant.
 */

/** The one supported wire-protocol identifier today. A STRING, not a number. */
const val JARVIS_PROTOCOL_VERSION: String = "1"

@Serializable
enum class RequestType { CHAT, COMMAND, COMPLEX, MEMORY, TOOL, PROACTIVE }

@Serializable
enum class ExecutionTarget { AUTO, FAST, BRAIN }

@Serializable
enum class ResponseStatus { OK, PARTIAL, ERROR }

/** Wire values are lower-case (str,Enum in jarvis-core serializes the VALUE). */
@Serializable
enum class FinishReason {
    @SerialName("stop") STOP,
    @SerialName("length") LENGTH,
    @SerialName("error") ERROR,
    @SerialName("cancelled") CANCELLED,
    @SerialName("timeout") TIMEOUT,
}

@Serializable
enum class StreamEventType {
    @SerialName("start") START,
    @SerialName("token") TOKEN,
    @SerialName("done") DONE,
    @SerialName("error") ERROR,
}

/**
 * Body of POST /v1/chat, /v1/ai/request, /v1/ai/stream. `requestId`,
 * `context` and `metadata` are legal on the wire but intentionally omitted
 * here: jarvis-core does not read `context`/`metadata` yet (see
 * jarvis-protocol README), and leaving `requestId` unset lets the server
 * generate one — one fewer thing this client has to get right.
 */
@Serializable
data class JarvisRequest(
    val protocolVersion: String = JARVIS_PROTOCOL_VERSION,
    val conversationId: String? = null,
    val requestType: RequestType = RequestType.CHAT,
    val text: String,
    val preferredTarget: ExecutionTarget = ExecutionTarget.AUTO,
    val allowFallback: Boolean = true,
)

/** Body of a 200 OK POST /v1/chat or /v1/ai/request response. */
@Serializable
data class JarvisResponse(
    val requestId: String,
    val status: ResponseStatus,
    val text: String = "",
    val modelUsed: String? = null,
    val targetUsed: ExecutionTarget? = null,
    val executionTimeMs: Double = 0.0,
    val tokensGenerated: Int = 0,
    val finishReason: FinishReason? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * One `data:` line of a POST /v1/ai/stream SSE response. jarvis-core sends
 * exactly one START, zero or more TOKEN, then exactly one of DONE or ERROR
 * (exclude_none=True server-side, so absent fields are simply not sent).
 */
@Serializable
data class StreamEvent(
    val type: StreamEventType,
    val requestId: String,
    val content: String? = null,
    val modelUsed: String? = null,
    val targetUsed: ExecutionTarget? = null,
    val executionTimeMs: Double? = null,
    val tokensGenerated: Int? = null,
    val finishReason: FinishReason? = null,
    val error: String? = null,
)

/** Body of GET /v1/health. Cheap/static — NOT a live backend check. */
@Serializable
data class HealthResponse(
    val status: String,
    val serverVersion: String,
    val protocolVersion: String,
    val uptimeSeconds: Double,
    val llmAvailable: Boolean,
    val activeModel: String? = null,
    val device: String,
    val timestamp: String,
)

/** Body of GET /v1/capabilities. The code-verified feature-flag list. */
@Serializable
data class CapabilitiesResponse(
    val chat: Boolean = true,
    val streaming: Boolean = true,
    val fastModel: Boolean = true,
    val brainModel: Boolean = true,
    val memory: Boolean = false,
    val rag: Boolean = false,
    val voice: Boolean = false,
    val vision: Boolean = false,
    val contextEngine: Boolean = false,
    val actions: Boolean = false,
    val protocolVersion: String = JARVIS_PROTOCOL_VERSION,
)
