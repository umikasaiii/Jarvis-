package com.simone.jarvismobile.corebridge

import kotlinx.serialization.Serializable

/**
 * Versioned wire models for JARVIS Core (the future PC companion server).
 * `protocolVersion` exists from day one so a later, incompatible Core build
 * can be detected instead of silently misparsed (§ richiesta esplicita:
 * "Prevedi data model versionati per evitare incompatibilità future").
 *
 * No PC server exists yet to verify these against a real payload — same
 * "written against the documented contract, not a live response" honesty
 * this project already applies to TomTom/Open-Meteo/Health Connect.
 */
const val CORE_PROTOCOL_VERSION = 1

@Serializable
enum class CoreRequestType { COMMAND, CHAT, COMPLEX, MEMORY, TOOL, PROACTIVE }

@Serializable
enum class CoreResponseStatus { OK, ERROR, PARTIAL }

/**
 * One request sent to `/v1/chat` or `/v1/ai/request`. [context] and
 * [preferredModel] are optional; [allowFallback] tells Core whether JARVIS
 * Android will itself retry locally on failure — Core can use this to decide
 * how hard to try before giving up vs erroring fast (§ "non inviare
 * automaticamente al PC più dati di quelli necessari": [context] carries
 * only what the caller explicitly put there, never an implicit full dump).
 */
@Serializable
data class JarvisCoreRequest(
    val requestId: String,
    val protocolVersion: Int = CORE_PROTOCOL_VERSION,
    val conversationId: String? = null,
    val timestamp: Long,
    val requestType: CoreRequestType,
    val text: String,
    val context: Map<String, String> = emptyMap(),
    val preferredModel: String? = null,
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

@Serializable
data class JarvisCoreResponse(
    val requestId: String,
    val status: CoreResponseStatus,
    val text: String? = null,
    val modelUsed: String? = null,
    val executionTimeMs: Long? = null,
    val toolCalls: List<CoreToolCall> = emptyList(),
    val memoryEvents: List<CoreMemoryEvent> = emptyList(),
    val error: String? = null,
)

/** One chunk of a `/v1/stream` response — [done] marks the terminal chunk (may carry the final [error]). */
@Serializable
data class JarvisCoreStreamChunk(
    val requestId: String,
    val delta: String = "",
    val done: Boolean = false,
    val error: String? = null,
)

/** Result of `GET /health`. */
@Serializable
data class CoreHealthResult(
    val reachable: Boolean,
    val serverVersion: String? = null,
    val protocolVersion: Int? = null,
)

/**
 * Result of [CoreClient.testConnection] — a richer, user-initiated probe
 * beyond the lightweight periodic [CoreHealthResult] heartbeat.
 */
@Serializable
data class CoreConnectionTestResult(
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val serverVersion: String? = null,
    val availableModels: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val error: String? = null,
)

/** Wire shape for `POST /v1/events` — deliberately minimal, mirrors `core.bridge.JarvisEvent` field for field. */
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
