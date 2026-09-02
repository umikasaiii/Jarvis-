package com.simone.jarvismobile.corebridge

import com.simone.jarvismobile.core.bridge.JarvisEvent
import kotlinx.coroutines.flow.Flow

/**
 * Communication boundary with JARVIS Core (the PC companion). Nothing above
 * this layer (ViewModel/Repository/AiRouter) talks to Retrofit/OkHttp/WebSocket
 * directly (§ richiesta esplicita) — everything goes through this interface,
 * bound to [JarvisCoreClientImpl] in `di/CoreModule.kt`, matching the same
 * `WeatherSource`-shaped-interface pattern already used for every other
 * fetcher in this codebase.
 */
interface CoreClient {
    /** Cheap `GET /health` — used for the periodic/cached heartbeat, never per-message. */
    suspend fun healthCheck(): CoreHealthResult

    /** A richer, user-initiated probe (`GET /health` + a models/capabilities query) for a manual "test connection" action. */
    suspend fun testConnection(): CoreConnectionTestResult

    /** `POST /v1/chat` or `/v1/ai/request` — one full, non-streamed answer. */
    suspend fun send(request: JarvisCoreRequest): JarvisCoreResponse

    /** `/v1/stream` — chunked partial output, terminated by a chunk with `done = true`. */
    fun stream(request: JarvisCoreRequest): Flow<JarvisCoreStreamChunk>

    /** Best-effort cancellation of an in-flight [send]/[stream] call for [requestId] (aborts the underlying OkHttp call). */
    fun cancel(requestId: String)

    /**
     * `POST /v1/events` — delivers one already-queued [JarvisEvent] to Core.
     * Returns `true` only on a confirmed (2xx) delivery, so [EventBridge] knows
     * it is safe to drop the event from its retry queue; any failure (network,
     * non-2xx, timeout) returns `false` and the event stays queued for the
     * next flush attempt.
     */
    suspend fun publishEvent(event: JarvisEvent): Boolean
}
