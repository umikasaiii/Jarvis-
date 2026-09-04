package com.simone.jarvismobile.corebridge

import android.util.Log
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Raw OkHttp implementation of [CoreClient] — no Retrofit anywhere in this
 * codebase (confirmed by grep across `app/`), so this matches every other
 * fetcher's house style (`OpenMeteoWeatherSource`, `TomTomRoutingEngine`,
 * `GoogleDriveRestClient`): its own lazily-built `OkHttpClient`, plain
 * `Request.Builder`, `kotlinx.serialization` for the JSON body, never a
 * shared/injected OkHttp singleton (none exists in this project either).
 *
 * Timeouts/host/port/https are read fresh from [SettingsRepository] on every
 * call rather than cached at construction — this class is a `@Singleton`,
 * but the user can change the server address without restarting the app.
 *
 * Endpoints and wire shapes match jarvis-protocol/main v1.0.0 exactly: `GET
 * /v1/health`, `GET /v1/capabilities`, `POST /v1/chat` (used for [send]),
 * `POST /v1/ai/stream` (real Server-Sent Events, via okhttp-sse — Core sends
 * discrete `start`/`token`/`done`/`error` events, not NDJSON).
 */
@Singleton
class JarvisCoreClientImpl @Inject constructor(
    private val settings: SettingsRepository,
) : CoreClient {

    private val json = Json {
        ignoreUnknownKeys = true // forward-compatible with additive protocol fields
        // kotlinx.serialization omits a property equal to its Kotlin default
        // from the OUTPUT JSON. protocolVersion has a Kotlin-side default for
        // convenience but is a REQUIRED field with no default in jarvis-core's
        // JarvisRequest — omitting it would fail server-side validation on
        // every call.
        encodeDefaults = true
    }
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val activeEventSources = ConcurrentHashMap<String, EventSource>()

    private suspend fun baseUrl(): String {
        val https = settings.coreHttps.first()
        val host = settings.coreHost.first()
        val port = settings.corePort.first()
        val scheme = if (https) "https" else "http"
        return "$scheme://$host:$port"
    }

    private suspend fun clientFor(timeoutMsOverride: Long? = null): OkHttpClient {
        val timeoutMs = timeoutMsOverride ?: settings.coreTimeoutMs.first().toLong()
        return OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            // A stream legitimately runs longer than the connect/health
            // timeout; callers cancel it explicitly via cancel(requestId).
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun healthCheck(): CoreHealthResult = withContext(Dispatchers.IO) {
        runCatching {
            val host = settings.coreHost.first()
            if (host.isBlank()) return@withContext CoreHealthResult(reachable = false)
            // A short, fixed timeout regardless of the configured request timeout —
            // a heartbeat must never itself become the thing that stalls the app
            // (§ "Health check... heartbeat leggero").
            val client = clientFor(timeoutMsOverride = HEALTH_CHECK_TIMEOUT_MS)
            val request = Request.Builder().url("${baseUrl()}/v1/health").get().build()
            val response = client.newCall(request).execute()
            response.use { r ->
                if (!r.isSuccessful) return@withContext CoreHealthResult(reachable = false)
                val body = r.body?.string()
                val parsed = body?.let { runCatching { json.decodeFromString(CoreHealthBody.serializer(), it) }.getOrNull() }
                if (parsed == null) {
                    CoreHealthResult(reachable = true)
                } else {
                    CoreHealthResult(
                        reachable = true,
                        serverVersion = parsed.serverVersion,
                        protocolVersion = parsed.protocolVersion,
                        llmAvailable = parsed.llmAvailable,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "core_health_failed ${it.javaClass.simpleName}") }
            .getOrDefault(CoreHealthResult(reachable = false))
    }

    override suspend fun testConnection(): CoreConnectionTestResult = withContext(Dispatchers.IO) {
        val host = settings.coreHost.first()
        if (host.isBlank()) return@withContext CoreConnectionTestResult(reachable = false, error = "host non configurato")
        val start = System.currentTimeMillis()
        val base = baseUrl()
        runCatching {
            val client = clientFor()
            val request = Request.Builder().url("$base/v1/health").get().build()
            val healthOutcome = client.newCall(request).execute().use { r ->
                val latency = System.currentTimeMillis() - start
                if (!r.isSuccessful) return@use null
                val body = r.body?.string()
                latency to body?.let { runCatching { json.decodeFromString(CoreHealthBody.serializer(), it) }.getOrNull() }
            } ?: return@withContext CoreConnectionTestResult(
                reachable = false,
                latencyMs = System.currentTimeMillis() - start,
                error = "http_error",
            )
            val (latency, health) = healthOutcome
            CoreConnectionTestResult(
                reachable = true,
                latencyMs = latency,
                serverVersion = health?.serverVersion,
                protocolVersion = health?.protocolVersion,
                llmAvailable = health?.llmAvailable,
                // No models-list endpoint in jarvis-protocol/main; capabilities
                // (fastModel/brainModel booleans) is the closest real signal.
                capabilities = fetchCapabilities(client, base),
            )
        }.getOrElse { e ->
            CoreConnectionTestResult(reachable = false, latencyMs = System.currentTimeMillis() - start, error = e.javaClass.simpleName)
        }
    }

    private fun fetchCapabilities(client: OkHttpClient, base: String): List<String> = runCatching {
        val request = Request.Builder().url("$base/v1/capabilities").get().build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return@use emptyList()
            val body = r.body?.string() ?: return@use emptyList()
            val caps = runCatching { json.decodeFromString(CoreCapabilitiesBody.serializer(), body) }.getOrNull()
                ?: return@use emptyList()
            buildList {
                if (caps.chat) add("chat")
                if (caps.streaming) add("streaming")
                if (caps.fastModel) add("fastModel")
                if (caps.brainModel) add("brainModel")
                if (caps.memory) add("memory")
                if (caps.rag) add("rag")
                if (caps.voice) add("voice")
                if (caps.vision) add("vision")
                if (caps.contextEngine) add("contextEngine")
                if (caps.actions) add("actions")
            }
        }
    }.getOrDefault(emptyList())

    /**
     * § audit "ENGINE_ERROR non mostra la causa reale": each phase (JSON
     * encode, `Request` build, the actual OkHttp call, response decode) is
     * now wrapped in its OWN try/catch instead of one blanket `runCatching`
     * around everything — a `runCatching` that wide can tell you *that*
     * something threw but not *where*, which is exactly what made every
     * failure before the server ever saw the request indistinguishable from
     * one it rejected. [errorResponse] tags every synthesized failure with
     * the phase it happened in, the real exception class/message when there
     * is one, and the endpoint/path actually targeted — carried verbatim in
     * [JarvisCoreResponse.error] up to [com.simone.jarvismobile.ai.RemoteAiEngine]
     * (see `AiEngineResult.errorDetail` in `com.simone.jarvismobile.ai.AiEngine`),
     * instead of collapsing to the bare [CoreResponseStatus.ERROR] enum.
     */
    override suspend fun send(request: JarvisCoreRequest): JarvisCoreResponse = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val path = "/v1/chat"

        val body = try {
            json.encodeToString(JarvisCoreRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext errorResponse(request.requestId, "serialization", e, base, path)
        }

        val client = clientFor()
        val httpRequest = try {
            Request.Builder().url("$base$path").post(body).build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext errorResponse(request.requestId, "request_creation", e, base, path)
        }

        val response = try {
            executeCancellable(client, httpRequest, request.requestId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext errorResponse(request.requestId, "http", e, base, path)
        }

        response.use { r ->
            val text = r.body?.string()
                ?: return@withContext errorResponse(request.requestId, "response_parsing", "empty_body", base, path)
            if (!r.isSuccessful) return@withContext errorResponse(request.requestId, "http", "http_${r.code}", base, path)
            try {
                json.decodeFromString(JarvisCoreResponse.serializer(), text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorResponse(request.requestId, "response_parsing", e, base, path)
            }
        }
    }

    /** Suspends until the response headers arrive, cancellable both by coroutine cancellation and by [cancel]. */
    private suspend fun executeCancellable(client: OkHttpClient, httpRequest: Request, requestId: String): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(httpRequest)
            activeCalls[requestId] = call
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCalls.remove(requestId)
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    activeCalls.remove(requestId)
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    /** A non-exception failure (bad HTTP status, empty body, …) — [reason] is a short fixed code, e.g. `"http_502"`. */
    private fun errorResponse(requestId: String, phase: String, reason: String, base: String, path: String): JarvisCoreResponse {
        Log.w(TAG, "core_send_failed phase=$phase reason=$reason")
        return JarvisCoreResponse(
            requestId = requestId,
            status = CoreResponseStatus.ERROR,
            error = "$phase:$reason @ $base$path",
        )
    }

    /** A real thrown exception — keeps its class AND message, never just the enum-shaped [AiFailureReason.ENGINE_ERROR] downstream. */
    private fun errorResponse(requestId: String, phase: String, e: Throwable, base: String, path: String): JarvisCoreResponse {
        Log.w(TAG, "core_send_failed phase=$phase ${e.javaClass.simpleName}: ${e.message}")
        return JarvisCoreResponse(
            requestId = requestId,
            status = CoreResponseStatus.ERROR,
            error = "$phase:${e.javaClass.simpleName}: ${e.message} @ $base$path",
        )
    }

    /**
     * `POST /v1/ai/stream` — real SSE via okhttp-sse. Translates Core's real
     * `start`/`token`/`done`/`error` events into the app-internal
     * [JarvisCoreStreamChunk] shape [RemoteAiEngine] already consumes, so
     * nothing above this layer needs to know SSE exists.
     */
    override fun stream(request: JarvisCoreRequest): Flow<JarvisCoreStreamChunk> = callbackFlow {
        val client = clientFor()
        val body = json.encodeToString(JarvisCoreRequest.serializer(), request)
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder().url("${baseUrl()}/v1/ai/stream").post(body).build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = runCatching { json.decodeFromString(CoreSseEvent.serializer(), data) }.getOrNull()
                val chunk = if (event == null) {
                    JarvisCoreStreamChunk(request.requestId, delta = data)
                } else {
                    mapSseEventToChunk(event)
                }
                if (chunk != null) trySend(chunk)
            }

            override fun onClosed(eventSource: EventSource) {
                activeEventSources.remove(request.requestId)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                activeEventSources.remove(request.requestId)
                val reason = when {
                    response != null -> "http_${response.code}"
                    t != null -> t.javaClass.simpleName
                    else -> "stream_failed"
                }
                trySend(JarvisCoreStreamChunk(request.requestId, done = true, error = reason))
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(httpRequest, listener)
        activeEventSources[request.requestId] = eventSource
        awaitClose {
            activeEventSources.remove(request.requestId)
            eventSource.cancel()
        }
    }

    override fun cancel(requestId: String) {
        activeCalls.remove(requestId)?.cancel()
        activeEventSources.remove(requestId)?.cancel()
    }

    override suspend fun describeEndpoint(): String = baseUrl()

    override suspend fun publishEvent(event: com.simone.jarvismobile.core.bridge.JarvisEvent): Boolean = withContext(Dispatchers.IO) {
        // jarvis-protocol/main defines no event-ingestion endpoint (verified
        // against jarvis-core's real routes: health/capabilities/models/chat/
        // ai/request/ai/stream only, no /v1/events). Never actually reached —
        // EventBridge.flushIfOnline() is gated off before calling this — kept
        // as a documented extension point, not invoked on the wire today.
        runCatching {
            val client = clientFor()
            val dto = CoreEventDto(
                id = event.id,
                type = event.type.name,
                timestampMs = event.timestampMs,
                source = event.source,
                priority = event.priority.name,
                privacyLevel = event.privacyLevel.name,
                payload = event.payload,
            )
            val body = json.encodeToString(CoreEventDto.serializer(), dto)
                .toRequestBody(JSON_MEDIA_TYPE)
            val httpRequest = Request.Builder().url("${baseUrl()}/v1/events").post(body).build()
            client.newCall(httpRequest).execute().use { r -> r.isSuccessful }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "core_publish_event_failed ${e.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        const val TAG = "JarvisCoreClient"
        const val HEALTH_CHECK_TIMEOUT_MS = 4_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Translates one real jarvis-protocol/main `/v1/ai/stream` SSE event into
 * the app-internal [JarvisCoreStreamChunk] shape — `null` means "nothing to
 * relay for this event" (only `start` today). Pulled out of
 * [JarvisCoreClientImpl] as a pure, Android-free function so it is directly
 * unit-testable without an `OkHttpClient`/`SettingsRepository`/`Context`.
 */
internal fun mapSseEventToChunk(event: CoreSseEvent): JarvisCoreStreamChunk? = when (event.type) {
    CoreSseEventType.START -> null
    CoreSseEventType.TOKEN -> JarvisCoreStreamChunk(event.requestId, delta = event.content.orEmpty())
    CoreSseEventType.DONE -> JarvisCoreStreamChunk(event.requestId, done = true)
    CoreSseEventType.ERROR -> JarvisCoreStreamChunk(event.requestId, done = true, error = event.error ?: "remote_error")
}
