package com.simone.jarvismobile.corebridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned wire models for JARVIS Core, matching jarvis-protocol/main
 * v1.0.0 (https://github.com/umikasaiii/Jarvis-protocol) field-for-field —
 * schemas/jarvis-request.schema.json, jarvis-response.schema.json,
 * stream-event.schema.json, health.schema.json, common.schema.json.
 * jarvis-protocol/main is the source of truth and is treated as immutable
 * from this repository: nothing here adds a field, endpoint or enum value
 * the protocol does not already declare.
 *
 * A real JARVIS Core server (github.com/umikasaiii/Jarvis-core) exists and
 * was audited directly to produce jarvis-protocol/main; these shapes are
 * verified against its real, tested implementation, not a guess.
 */

/** The one supported wire-protocol identifier today. A STRING, not a number. */
const val CORE_PROTOCOL_VERSION: String = "1"

@Serializable
enum class CoreRequestType { COMMAND, CHAT, COMPLEX, MEMORY, TOOL, PROACTIVE }

@Serializable
enum class CoreResponseStatus { OK, ERROR, PARTIAL }

/** `preferredTarget` on the wire. AUTO lets Core's own router decide FAST vs BRAIN. */
@Serializable
enum class CoreExecutionTarget { AUTO, FAST, BRAIN }

/**
 * One request sent to `/v1/chat` or `/v1/ai/request`/`/v1/ai/stream`.
 * [context] and [preferredTarget] are optional; [allowFallback] tells Core
 * whether it may itself substitute FAST<->BRAIN on backend failure (§ "non
 * inviare automaticamente al PC più dati di quelli necessari": [context]
 * carries only what the caller explicitly put there, never an implicit
 * full dump).
 */
@Serializable
data class JarvisCoreRequest(
    val requestId: String,
    val protocolVersion: String = CORE_PROTOCOL_VERSION,
    val conversationId: String? = null,
    val requestType: CoreRequestType,
    val text: String,
    val context: Map<String, String> = emptyMap(),
    val preferredTarget: CoreExecutionTarget = CoreExecutionTarget.AUTO,
    val allowFallback: Boolean = true,
)

@Serializable
data class CoreToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

@Serializable
data class CoreMemoryEvent(
    val kind: String,
    val summary: String,
)

/**
 * Body of a 200 OK `/v1/chat` or `/v1/ai/request` response.
 * [toolCalls]/[memoryEvents] are NOT part of jarvis-protocol/main — Core
 * never populates them today (kept, always empty, as a documented,
 * forward-looking extension point; not a live server capability).
 */
@Serializable
data class JarvisCoreResponse(
    val requestId: String,
    val status: CoreResponseStatus,
    val text: String? = null,
    val modelUsed: String? = null,
    val targetUsed: CoreExecutionTarget? = null,
    val executionTimeMs: Double? = null,
    val finishReason: String? = null,
    val warnings: List<String> = emptyList(),
    val toolCalls: List<CoreToolCall> = emptyList(),
    val memoryEvents: List<CoreMemoryEvent> = emptyList(),
    val error: String? = null,
)

/**
 * One app-internal streamed chunk — NOT the wire shape. jarvis-core's real
 * `/v1/ai/stream` sends discrete SSE `start`/`token`/`done`/`error` events
 * (see [CoreSseEventType]/[CoreSseEvent] below); [JarvisCoreClientImpl]
 * translates those into this simpler delta/done shape so
 * [com.simone.jarvismobile.ai.RemoteAiEngine]/[com.simone.jarvismobile.ai.AiRouter]
 * do not need to know about SSE at all. [done] marks the terminal chunk
 * (may carry the final [error]).
 */
@Serializable
data class JarvisCoreStreamChunk(
    val requestId: String,
    val delta: String = "",
    val done: Boolean = false,
    val error: String? = null,
)

/** Wire value of one SSE `data:` line's `type` field on `/v1/ai/stream`. Lower-case on the wire. */
@Serializable
enum class CoreSseEventType {
    @SerialName("start") START,
    @SerialName("token") TOKEN,
    @SerialName("done") DONE,
    @SerialName("error") ERROR,
}

/**
 * Wire-exact shape of one `/v1/ai/stream` SSE event
 * (schemas/stream-event.schema.json). Decode-only — [JarvisCoreClientImpl]
 * is the only place this type is ever touched; everything else uses
 * [JarvisCoreStreamChunk].
 */
@Serializable
data class CoreSseEvent(
    val type: CoreSseEventType,
    val requestId: String,
    val content: String? = null,
    val modelUsed: String? = null,
    val targetUsed: CoreExecutionTarget? = null,
    val executionTimeMs: Double? = null,
    val tokensGenerated: Int? = null,
    val finishReason: String? = null,
    val error: String? = null,
)

/**
 * Result of `GET /v1/health`. Wire-exact
 * (schemas/health.schema.json) — Core always returns all eight fields.
 * `llmAvailable` is hardcoded `true` server-side today (not a live backend
 * check — see jarvis-protocol/main README); kept here for forward
 * compatibility, not relied on for anything beyond DEGRADED classification.
 */
@Serializable
data class CoreHealthBody(
    val status: String,
    val serverVersion: String,
    val protocolVersion: String,
    val uptimeSeconds: Double,
    val llmAvailable: Boolean,
    val activeModel: String? = null,
    val device: String,
    val timestamp: String,
)

/** App-internal result of a health probe — never decoded directly from the wire (see [CoreHealthBody]). */
data class CoreHealthResult(
    val reachable: Boolean,
    val serverVersion: String? = null,
    val protocolVersion: String? = null,
    val llmAvailable: Boolean? = null,
)

/**
 * Result of [CoreClient.testConnection] — a richer, user-initiated probe
 * beyond the lightweight periodic [CoreHealthResult] heartbeat. Backed by
 * `GET /v1/health` + `GET /v1/capabilities` (the only two real, cheap
 * "what does this Core build support" endpoints jarvis-protocol/main
 * defines — there is no separate models-list endpoint in this contract).
 */
@Serializable
data class CoreConnectionTestResult(
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val serverVersion: String? = null,
    val protocolVersion: String? = null,
    val llmAvailable: Boolean? = null,
    val availableModels: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val error: String? = null,
)

/** Body of `GET /v1/capabilities` (schemas/capabilities.schema.json). */
@Serializable
data class CoreCapabilitiesBody(
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
    val protocolVersion: String = CORE_PROTOCOL_VERSION,
)

/**
 * Wire shape for `POST /v1/events` — mirrors `core.bridge.JarvisEvent` field
 * for field. NOT part of jarvis-protocol/main: jarvis-core has no event-
 * ingestion endpoint today (verified against its real routes). Kept,
 * unused, as a documented extension point — [EventBridge] never actually
 * calls [CoreClient.publishEvent] today (see its own doc comment) so this
 * is never sent on the wire.
 */
@Serializable
data class CoreEventDto(
    val id: String,
    val type: String,
    val timestampMs: Long,
    val source: String,
    val priority: String,
    val privacyLevel: String,
    val payload: Map<String, String> = emptyMap(),
)
