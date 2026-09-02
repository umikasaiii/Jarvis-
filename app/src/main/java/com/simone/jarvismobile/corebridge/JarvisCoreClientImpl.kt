package com.simone.jarvismobile.corebridge

import android.util.Log
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * **Onestà**: no real JARVIS Core server exists yet to verify these request
 * shapes/endpoints against (§ richiesta esplicita: "Non è necessario che il
 * vero server PC esista già"). The three endpoints below (`GET /health`,
 * `POST /v1/chat` or `/v1/ai/request`, `/v1/stream`) are exactly the ones
 * named in the request; the streaming transport is plain HTTP with
 * newline-delimited JSON chunks (NDJSON) rather than a WebSocket, since it
 * needs no extra dependency and OkHttp already handles it — first real
 * verification happens once an actual Core build exists to test against.
 */
@Singleton
class JarvisCoreClientImpl @Inject constructor(
    private val settings: SettingsRepository,
) : CoreClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val activeCalls = ConcurrentHashMap<String, Call>()

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
            val request = Request.Builder().url("${baseUrl()}/health").get().build()
            val response = client.newCall(request).execute()
            response.use { r ->
                if (!r.isSuccessful) return@withContext CoreHealthResult(reachable = false)
                val body = r.body?.string()
                val parsed = body?.let { runCatching { json.decodeFromString(CoreHealthResult.serializer(), it) }.getOrNull() }
                parsed?.copy(reachable = true) ?: CoreHealthResult(reachable = true)
            }
        }.onFailure { Log.w(TAG, "core_health_failed ${it.javaClass.simpleName}") }
            .getOrDefault(CoreHealthResult(reachable = false))
    }

    override suspend fun testConnection(): CoreConnectionTestResult = withContext(Dispatchers.IO) {
        val host = settings.coreHost.first()
        if (host.isBlank()) return@withContext CoreConnectionTestResult(reachable = false, error = "host non configurato")
        val start = System.currentTimeMillis()
        runCatching {
            val client = clientFor()
            val request = Request.Builder().url("${baseUrl()}/health").get().build()
            client.newCall(request).execute().use { r ->
                val latency = System.currentTimeMillis() - start
                if (!r.isSuccessful) {
                    return@withContext CoreConnectionTestResult(reachable = false, latencyMs = latency, error = "http_${r.code}")
                }
                val body = r.body?.string()
                val health = body?.let { runCatching { json.decodeFromString(CoreHealthResult.serializer(), it) }.getOrNull() }
                CoreConnectionTestResult(
                    reachable = true,
                    latencyMs = latency,
                    serverVersion = health?.serverVersion,
                    // Modelli/capabilities: nessun endpoint dedicato ancora definito nel
                    // protocollo — un vero Core futuro dovrà esporli in /health o un
                    // endpoint proprio; per ora restano vuoti, mai inventati.
                )
            }
        }.getOrElse { e ->
            CoreConnectionTestResult(reachable = false, latencyMs = System.currentTimeMillis() - start, error = e.javaClass.simpleName)
        }
    }

    override suspend fun send(request: JarvisCoreRequest): JarvisCoreResponse = withContext(Dispatchers.IO) {
        runCatching {
            val client = clientFor()
            val body = json.encodeToString(JarvisCoreRequest.serializer(), request)
                .toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder().url("${baseUrl()}/v1/chat").post(body).build()
            executeCancellable(client, httpRequest, request.requestId).use { r ->
                val text = r.body?.string()
                    ?: return@withContext errorResponse(request.requestId, "empty_body")
                if (!r.isSuccessful) return@withContext errorResponse(request.requestId, "http_${r.code}")
                runCatching { json.decodeFromString(JarvisCoreResponse.serializer(), text) }
                    .getOrElse { errorResponse(request.requestId, "decode_failed") }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "core_send_failed ${e.javaClass.simpleName}")
            errorResponse(request.requestId, e.javaClass.simpleName)
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

    private fun errorResponse(requestId: String, reason: String) =
        JarvisCoreResponse(requestId = requestId, status = CoreResponseStatus.ERROR, error = reason)

    override fun stream(request: JarvisCoreRequest): Flow<JarvisCoreStreamChunk> = callbackFlow {
        val client = clientFor()
        val body = json.encodeToString(JarvisCoreRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder().url("${baseUrl()}/v1/stream").post(body).build()
        val call = client.newCall(httpRequest)
        activeCalls[request.requestId] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCalls.remove(request.requestId)
                trySend(JarvisCoreStreamChunk(request.requestId, done = true, error = e.javaClass.simpleName))
                close()
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        trySend(JarvisCoreStreamChunk(request.requestId, done = true, error = "http_${r.code}"))
                        activeCalls.remove(request.requestId)
                        close()
                        return
                    }
                    val source = r.body?.source()
                    runCatching {
                        while (source != null && !source.exhausted() && !isClosedForSend) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val chunk = runCatching { json.decodeFromString(JarvisCoreStreamChunk.serializer(), line) }
                                .getOrElse { JarvisCoreStreamChunk(request.requestId, delta = line) }
                            trySend(chunk)
                            if (chunk.done) break
                        }
                    }.onFailure { e ->
                        if (e !is CancellationException) trySend(JarvisCoreStreamChunk(request.requestId, done = true, error = e.javaClass.simpleName))
                    }
                    activeCalls.remove(request.requestId)
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }

    override fun cancel(requestId: String) {
        activeCalls.remove(requestId)?.cancel()
    }

    override suspend fun publishEvent(event: com.simone.jarvismobile.core.bridge.JarvisEvent): Boolean = withContext(Dispatchers.IO) {
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
                .toRequestBody("application/json".toMediaType())
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
    }
}
