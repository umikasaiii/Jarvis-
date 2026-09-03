package com.simone.jarvismobile.core.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Everything needed to reach one JARVIS Core instance. No IP is hardcoded anywhere. */
data class CoreClientConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val useHttps: Boolean = false,
    val timeoutMs: Long = 10_000L,
    val apiToken: String? = null,
) {
    val baseUrl: String get() = "${if (useHttps) "https" else "http"}://$host:$port"
}

/**
 * Every call this client can fail in, distinguished so a caller (the AI
 * router, the UI) can react without re-deriving "what kind of failure was
 * this" from an exception message. jarvis-core has three different real
 * error-body shapes (see jarvis-protocol/main README > "Known
 * inconsistency"); this client absorbs that difference here so nothing else
 * has to know about it, and does so WITHOUT changing the protocol.
 */
sealed interface CoreResult<out T> {
    data class Success<T>(val value: T) : CoreResult<T>

    sealed interface Failure : CoreResult<Nothing> {
        /** Core reachable, but rejected the request/returned an error status. */
        data class Http(val code: Int, val errorCode: String?, val message: String) : Failure

        /** Core is running a different protocolVersion than this client sends. */
        data class ProtocolMismatch(val expected: String, val received: String?, val message: String) : Failure

        /** Could not even reach Core: PC off, wrong IP, no Wi-Fi, connection refused, DNS. */
        data class Network(val message: String) : Failure

        data class Timeout(val message: String) : Failure

        /** Core answered but the body did not parse as the expected shape. */
        data class Malformed(val message: String) : Failure

        data object Cancelled : Failure
    }
}

/**
 * Thin, jarvis-protocol-v1-exact HTTP client for JARVIS Core. Supports only
 * the endpoints Android actually needs: GET /v1/health, GET /v1/capabilities,
 * POST /v1/ai/request, POST /v1/ai/stream. No endpoint outside jarvis-protocol
 * is ever called.
 *
 * Pure JVM (no Android dependency) so it compiles and is unit-testable here
 * against MockWebServer without the Android SDK.
 */
class JarvisCoreClient(
    private val config: CoreClientConfig,
    baseClient: OkHttpClient = OkHttpClient(),
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        // A stream legitimately runs longer than the connect/health timeout;
        // callers cancel it explicitly instead of relying on a call timeout.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true // forward-compatible with additive protocol fields
        explicitNulls = false
        // Required: kotlinx.serialization omits a property from the OUTPUT
        // JSON when its value equals its Kotlin default. protocolVersion has
        // a Kotlin-side default (for convenience) but is a REQUIRED field
        // with NO default in jarvis-core's JarvisRequest — omitting it would
        // make the request fail server-side validation on every call.
        encodeDefaults = true
    }

    private val sseFactory: EventSource.Factory = EventSources.createFactory(client)

    private fun url(path: String) = "${config.baseUrl}$path"

    private fun Request.Builder.withAuth(): Request.Builder = apply {
        config.apiToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    suspend fun health(): CoreResult<HealthResponse> =
        get("/v1/health") { json.decodeFromString(HealthResponse.serializer(), it) }

    suspend fun capabilities(): CoreResult<CapabilitiesResponse> =
        get("/v1/capabilities") { json.decodeFromString(CapabilitiesResponse.serializer(), it) }

    /** POST /v1/ai/request — non-streaming. */
    suspend fun request(body: JarvisRequest): CoreResult<JarvisResponse> {
        val validated = body.copy(protocolVersion = JARVIS_PROTOCOL_VERSION)
        val payload = json.encodeToString(JarvisRequest.serializer(), validated)
        val httpRequest = Request.Builder()
            .url(url("/v1/ai/request"))
            .withAuth()
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(httpRequest) { code, responseBody ->
            if (code == 200) {
                runCatching { json.decodeFromString(JarvisResponse.serializer(), responseBody) }
                    .fold({ CoreResult.Success(it) }, { malformed(it) })
            } else {
                errorResult(code, responseBody)
            }
        }
    }

    /**
     * POST /v1/ai/stream — Server-Sent Events. Emits one [CoreResult] per
     * frame (Success(StreamEvent) for every start/token/done/error event
     * actually sent by Core) plus, when the stream cannot even be opened
     * (network failure, protocol mismatch caught before SSE begins,
     * malformed pre-flight body), exactly one terminal [CoreResult.Failure].
     * Cancelling collection of the returned [Flow] closes the underlying
     * connection immediately — no inference is left running client-side.
     */
    fun stream(body: JarvisRequest): Flow<CoreResult<StreamEvent>> = callbackFlow {
        val validated = body.copy(protocolVersion = JARVIS_PROTOCOL_VERSION)
        val payload = json.encodeToString(JarvisRequest.serializer(), validated)
        val httpRequest = Request.Builder()
            .url(url("/v1/ai/stream"))
            .withAuth()
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val parsed = runCatching { json.decodeFromString(StreamEvent.serializer(), data) }
                    .fold({ CoreResult.Success(it) }, { malformed<StreamEvent>(it) })
                trySend(parsed)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val result: CoreResult<StreamEvent> = when {
                    response != null -> {
                        val code = response.code
                        val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
                        errorResultSync(code, text)
                    }
                    t is java.net.SocketTimeoutException -> CoreResult.Failure.Timeout(t.message ?: "timeout")
                    t is IOException -> CoreResult.Failure.Network(t.message ?: "network_error")
                    else -> CoreResult.Failure.Network(t?.message ?: "stream_failed")
                }
                trySend(result)
                close()
            }
        }

        val eventSource = sseFactory.newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    // --- internals -----------------------------------------------------

    private suspend fun <T> get(path: String, decode: (String) -> T): CoreResult<T> {
        val httpRequest = Request.Builder().url(url(path)).withAuth().get().build()
        return execute(httpRequest) { code, responseBody ->
            if (code == 200) {
                runCatching { decode(responseBody) }.fold({ CoreResult.Success(it) }, { malformed(it) })
            } else {
                errorResult(code, responseBody)
            }
        }
    }

    private suspend fun <T> execute(
        request: Request,
        onResponse: (code: Int, body: String) -> CoreResult<T>,
    ): CoreResult<T> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                val failure: CoreResult<T> = when (e) {
                    is java.net.SocketTimeoutException -> CoreResult.Failure.Timeout(e.message ?: "timeout")
                    else -> CoreResult.Failure.Network(e.message ?: "network_error")
                }
                cont.resume(failure)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val bodyText = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    if (!cont.isCancelled) cont.resume(onResponse(resp.code, bodyText))
                }
            }
        })
    }

    private fun <T> malformed(t: Throwable): CoreResult<T> =
        CoreResult.Failure.Malformed(t.message ?: "malformed_response")

    /** Parses jarvis-core's three real error shapes (see jarvis-protocol README). */
    private fun <T> errorResult(code: Int, body: String): CoreResult<T> = errorResultSync(code, body)

    private fun <T> errorResultSync(code: Int, body: String): CoreResult<T> {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject

        if (code == 400) {
            // protocolVersion mismatch: default FastAPI HTTPException body
            // {"detail": "Unsupported protocolVersion 'X', server expects 'Y'"}.
            val detail = element?.get("detail")?.jsonPrimitiveOrNull()
            if (detail != null && "protocolVersion" in detail) {
                val received = Regex("protocolVersion '([^']*)'").find(detail)?.groupValues?.get(1)
                return CoreResult.Failure.ProtocolMismatch(
                    expected = JARVIS_PROTOCOL_VERSION,
                    received = received,
                    message = detail,
                )
            }
        }

        val errorCode = element?.get("error")?.jsonPrimitiveOrNull()
        val detailField = element?.get("detail")
        val message = when {
            errorCode != null && detailField is JsonArray ->
                "$errorCode: ${detailField.joinToString { it.toString() }}"
            errorCode != null -> errorCode
            detailField != null -> detailField.jsonPrimitiveOrNull() ?: detailField.toString()
            body.isNotBlank() -> body.take(200)
            else -> "http_$code"
        }
        return CoreResult.Failure.Http(code = code, errorCode = errorCode, message = message)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
