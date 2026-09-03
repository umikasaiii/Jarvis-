package com.simone.jarvismobile.core.remote

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises [JarvisCoreClient] against a real local HTTP server
 * (okhttp3.mockwebserver) — no Android SDK needed, no mocked-out networking.
 * These tests use runBlocking (not runTest): they do genuine, if local and
 * fast, socket I/O, which does not mix well with kotlinx-coroutines-test's
 * virtual-time scheduler.
 */
class JarvisCoreClientTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun client(timeoutMs: Long = 3_000) =
        JarvisCoreClient(CoreClientConfig(enabled = true, host = server.hostName, port = server.port, timeoutMs = timeoutMs))

    // --- health / capabilities ------------------------------------------

    @Test
    fun `Core online - health decodes correctly`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"online","serverVersion":"0.1.0","protocolVersion":"1","uptimeSeconds":1.0,"llmAvailable":true,"activeModel":"fast-fake","device":"pc","timestamp":"2026-01-15T10:30:00+00:00"}""",
            ).addHeader("Content-Type", "application/json"),
        )
        val result = client().health()
        assertIs<CoreResult.Success<HealthResponse>>(result)
        assertEquals("online", result.value.status)
        assertEquals(JARVIS_PROTOCOL_VERSION, result.value.protocolVersion)
    }

    @Test
    fun `Core offline - connection refused surfaces as Network failure`() = runBlocking {
        val deadPort = server.port
        server.shutdown() // nothing listens on deadPort anymore
        val result = JarvisCoreClient(CoreClientConfig(enabled = true, host = "127.0.0.1", port = deadPort, timeoutMs = 2_000)).health()
        assertIs<CoreResult.Failure.Network>(result)
    }

    @Test
    fun `wrong IP - timeout surfaces as Timeout failure, not a hang`() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(5, TimeUnit.SECONDS))
        val result = withTimeout(2_000) { client(timeoutMs = 300).health() }
        assertIs<CoreResult.Failure.Timeout>(result)
    }

    @Test
    fun `capabilities decodes correctly`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"chat":true,"streaming":true,"fastModel":true,"brainModel":true,"memory":false,"rag":false,"voice":false,"vision":false,"contextEngine":false,"actions":false,"protocolVersion":"1"}""",
            ),
        )
        val result = client().capabilities()
        assertIs<CoreResult.Success<CapabilitiesResponse>>(result)
        assertTrue(result.value.chat)
        assertTrue(result.value.streaming)
    }

    // --- /v1/ai/request ---------------------------------------------------

    @Test
    fun `valid response decodes into JarvisResponse`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"requestId":"r1","status":"OK","text":"ciao","modelUsed":"fast-fake","targetUsed":"FAST","executionTimeMs":5.0,"tokensGenerated":2,"finishReason":"stop","warnings":[],"error":null}""",
            ),
        )
        val result = client().request(JarvisRequest(text = "ciao"))
        assertIs<CoreResult.Success<JarvisResponse>>(result)
        assertEquals(ResponseStatus.OK, result.value.status)
        assertEquals("ciao", result.value.text)
    }

    @Test
    fun `malformed response body is reported, not thrown`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"not": "a jarvis response"""))
        val result = client().request(JarvisRequest(text = "ciao"))
        assertIs<CoreResult.Failure.Malformed>(result)
    }

    @Test
    fun `protocol mismatch (HTTP 400) is recognized distinctly from a generic error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"detail": "Unsupported protocolVersion '99', server expects '1'"}""",
            ),
        )
        val result = client().request(JarvisRequest(text = "ciao"))
        assertIs<CoreResult.Failure.ProtocolMismatch>(result)
        assertEquals("1", result.expected)
        assertEquals("99", result.received)
    }

    @Test
    fun `validation error (HTTP 422, error+detail shape) is a Http failure`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"error":"invalid_request","detail":[{"type":"missing","loc":["body","text"],"msg":"Field required"}]}""",
            ),
        )
        val result = client().request(JarvisRequest(text = "ciao"))
        assertIs<CoreResult.Failure.Http>(result)
        assertEquals(422, result.code)
        assertEquals("invalid_request", result.errorCode)
    }

    @Test
    fun `rate limited (HTTP 429, bare error shape) is a Http failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate_limited"}"""))
        val result = client().request(JarvisRequest(text = "ciao"))
        assertIs<CoreResult.Failure.Http>(result)
        assertEquals(429, result.code)
        assertEquals("rate_limited", result.errorCode)
    }

    // --- /v1/ai/stream -----------------------------------------------------

    @Test
    fun `SSE start-token-done sequence is decoded in order`() = runBlocking {
        val body = buildString {
            append("data: {\"type\":\"start\",\"requestId\":\"r1\",\"targetUsed\":\"FAST\"}\n\n")
            append("data: {\"type\":\"token\",\"requestId\":\"r1\",\"content\":\"Ciao\"}\n\n")
            append("data: {\"type\":\"token\",\"requestId\":\"r1\",\"content\":\" mondo\"}\n\n")
            append(
                "data: {\"type\":\"done\",\"requestId\":\"r1\",\"modelUsed\":\"fast-fake\"," +
                    "\"targetUsed\":\"FAST\",\"executionTimeMs\":9.0,\"tokensGenerated\":2,\"finishReason\":\"stop\"}\n\n",
            )
        }
        server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(body))

        val events = client().stream(JarvisRequest(text = "ciao")).toList()
        assertEquals(4, events.size)
        val types = events.map { (it as CoreResult.Success).value.type }
        assertEquals(
            listOf(StreamEventType.START, StreamEventType.TOKEN, StreamEventType.TOKEN, StreamEventType.DONE),
            types,
        )
        assertEquals("Ciao", (events[1] as CoreResult.Success).value.content)
    }

    @Test
    fun `a Core-level stream error event is delivered as data, not a client failure`() = runBlocking {
        val body = "data: {\"type\":\"start\",\"requestId\":\"r1\"}\n\n" +
            "data: {\"type\":\"error\",\"requestId\":\"r1\",\"error\":\"brain-fake backend unavailable\"}\n\n"
        server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(body))

        val events = client().stream(JarvisRequest(text = "ciao")).toList()
        assertEquals(2, events.size)
        val last = (events[1] as CoreResult.Success).value
        assertEquals(StreamEventType.ERROR, last.type)
        assertEquals("brain-fake backend unavailable", last.error)
    }

    @Test
    fun `cancelling the flow closes the connection instead of hanging`() = runBlocking {
        val chunk = "data: {\"type\":\"token\",\"requestId\":\"r1\",\"content\":\"a\"}\n\n"
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(chunk.repeat(200))
                .throttleBody(64, 100, TimeUnit.MILLISECONDS),
        )

        val received = mutableListOf<CoreResult<StreamEvent>>()
        val job = launch { client(timeoutMs = 10_000).stream(JarvisRequest(text = "x")).collect { received += it } }

        withTimeout(3_000) { while (received.isEmpty()) delay(10) }
        withTimeout(2_000) { job.cancelAndJoin() } // must not hang: proves the connection was actually closed
        assertTrue(received.isNotEmpty())
    }
}
