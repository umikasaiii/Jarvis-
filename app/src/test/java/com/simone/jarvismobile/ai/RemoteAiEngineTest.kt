package com.simone.jarvismobile.ai

import com.simone.jarvismobile.core.ai.AiExecutionTarget
import com.simone.jarvismobile.core.ai.AiFailureReason
import com.simone.jarvismobile.core.ai.AiRequestType
import com.simone.jarvismobile.core.bridge.JarvisEvent
import com.simone.jarvismobile.corebridge.CoreClient
import com.simone.jarvismobile.corebridge.CoreConnectionTestResult
import com.simone.jarvismobile.corebridge.CoreHealthResult
import com.simone.jarvismobile.corebridge.CoreResponseStatus
import com.simone.jarvismobile.corebridge.JarvisCoreRequest
import com.simone.jarvismobile.corebridge.JarvisCoreResponse
import com.simone.jarvismobile.corebridge.JarvisCoreStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [RemoteAiEngine] is the one client both `SessionCoordinator.tryRemoteChat`
 * (Motore Classico) and `JarvisBrain.tryRemoteReply` (Motore Conversazionale,
 * § FASE SUCCESSIVA) share (§ "NON creare un secondo router/client/pipeline")
 * — covering it once here proves the real request/failure behaviour both
 * call sites depend on, without needing either caller's 30+ Android
 * dependencies (`CoreClient` is a plain interface, fakeable).
 */
class RemoteAiEngineTest {

    private fun request(
        type: AiRequestType = AiRequestType.CHAT,
        systemPrompt: String = "Sei JARVIS. Rispondi solo in JSON.",
    ) = AiRequest(
        requestId = "req-1",
        text = "Rispondi solo: TEST CORE",
        systemPrompt = systemPrompt,
        requestType = type,
    )

    @Test
    fun `generate forwards systemPrompt to Core verbatim - the exact gap this round closes`() = runTest {
        var seen: JarvisCoreRequest? = null
        val client = FakeCoreClient(sendResult = { req -> seen = req; okResponse(req.requestId) })
        val engine = RemoteAiEngine(client)

        engine.generate(request(systemPrompt = "PERSONA + PROTOCOL_BLOCK + TOOL CATALOG"))

        assertEquals("PERSONA + PROTOCOL_BLOCK + TOOL CATALOG", seen?.systemPrompt)
    }

    @Test
    fun `blank systemPrompt is sent as null, never an empty string`() = runTest {
        var seen: JarvisCoreRequest? = null
        val client = FakeCoreClient(sendResult = { req -> seen = req; okResponse(req.requestId) })
        val engine = RemoteAiEngine(client)

        engine.generate(request(systemPrompt = "   "))

        assertNull(seen?.systemPrompt)
    }

    /**
     * § audit "ENGINE_ERROR: http:http_422": `jarvis-protocol/main`'s
     * `systemPrompt` has `maxLength: 8000` — `JarvisBrain`'s real system
     * prompt (persona + protocol block + the ~53-tool catalog) runs past
     * that on this device and jarvis-core rejects the whole request with a
     * plain 422, which is exactly what made every Conversational-engine
     * remote turn fail with zero visible cause before this round's
     * ENGINE_ERROR-detail fix surfaced it. This pins the actual fix: the
     * WIRE copy never exceeds the protocol limit.
     */
    @Test
    fun `a systemPrompt over the protocol's 8000-char limit is truncated on the wire`() = runTest {
        var seen: JarvisCoreRequest? = null
        val client = FakeCoreClient(sendResult = { req -> seen = req; okResponse(req.requestId) })
        val engine = RemoteAiEngine(client)
        val huge = "x".repeat(9000)

        engine.generate(request(systemPrompt = huge))

        assertTrue("wire systemPrompt must never exceed the protocol limit", (seen?.systemPrompt?.length ?: 0) <= 8000)
        assertTrue("truncation must actually shorten it, not just pass it through", (seen?.systemPrompt?.length ?: 0) < huge.length)
    }

    @Test
    fun `truncation lands on a full line, never a tool description cut off mid-word`() = runTest {
        var seen: JarvisCoreRequest? = null
        val client = FakeCoreClient(sendResult = { req -> seen = req; okResponse(req.requestId) })
        val engine = RemoteAiEngine(client)
        // 200 complete "- toolN: description\n" lines, each well past the 8000-char budget combined.
        val catalog = (1..500).joinToString("") { "- tool$it: a reasonably long description of tool number $it\n" }

        engine.generate(request(systemPrompt = catalog))

        val wire = seen?.systemPrompt ?: ""
        assertTrue(wire.length <= 8000)
        assertTrue("the wire copy must be a verbatim prefix of the real catalog", catalog.startsWith(wire))
        val nextChar = catalog.getOrNull(wire.length)
        assertTrue("the cut must land right before a newline, never mid-tool-description", nextChar == null || nextChar == '\n')
    }

    @Test
    fun `a systemPrompt within the limit reaches Core byte-for-byte, no truncation`() = runTest {
        var seen: JarvisCoreRequest? = null
        val client = FakeCoreClient(sendResult = { req -> seen = req; okResponse(req.requestId) })
        val engine = RemoteAiEngine(client)
        val normal = "x".repeat(7999)

        engine.generate(request(systemPrompt = normal))

        assertEquals(normal, seen?.systemPrompt)
    }

    @Test
    fun `successful reply is reported as success with the reply text`() = runTest {
        val client = FakeCoreClient(sendResult = { req -> okResponse(req.requestId, text = "TEST CORE") })
        val engine = RemoteAiEngine(client)

        val result = engine.generate(request())

        assertTrue(result.success)
        assertEquals("TEST CORE", result.text)
        assertEquals(AiExecutionTarget.REMOTE_FAST, result.target)
    }

    @Test
    fun `Core offline (network failure) is a recoverable failure, never an exception escaping`() = runTest {
        val client = FakeCoreClient(sendThrows = IOException("connection refused"))
        val engine = RemoteAiEngine(client)

        val result = engine.generate(request())

        assertFalse(result.success)
        assertEquals(AiFailureReason.NETWORK, result.failureReason)
    }

    @Test
    fun `a server ERROR status maps to a recoverable ENGINE_ERROR failure`() = runTest {
        val client = FakeCoreClient(
            sendResult = { req ->
                JarvisCoreResponse(requestId = req.requestId, status = CoreResponseStatus.ERROR, text = null)
            },
        )
        val engine = RemoteAiEngine(client)

        val result = engine.generate(request())

        assertFalse(result.success)
        assertEquals(AiFailureReason.ENGINE_ERROR, result.failureReason)
    }

    /**
     * § audit "tryRemoteReply produce ENGINE_ERROR ma il terminale FastAPI non
     * vede alcuna POST": `JarvisCoreClientImpl.send()` now tags every
     * synthesized [CoreResponseStatus.ERROR] with the real phase/exception
     * that produced it (e.g. `"http:ConnectException: Connection refused @
     * http://192.168.1.10:8000/v1/chat"` for a failure BEFORE the server ever
     * saw the request) in [JarvisCoreResponse.error] — this pins that
     * [RemoteAiEngine.generate] actually forwards that string into
     * [AiEngineResult.errorDetail] instead of discarding it down to the bare
     * `ENGINE_ERROR` enum name, which is exactly what made every pre-HTTP
     * failure indistinguishable from a real server-side error before this
     * fix.
     */
    @Test
    fun `a phase-tagged ERROR detail from the client survives into errorDetail, not just the bare enum`() = runTest {
        val detail = "http:ConnectException: Connection refused @ http://192.168.1.10:8000/v1/chat"
        val client = FakeCoreClient(
            sendResult = { req ->
                JarvisCoreResponse(requestId = req.requestId, status = CoreResponseStatus.ERROR, error = detail)
            },
        )
        val engine = RemoteAiEngine(client)

        val result = engine.generate(request())

        assertFalse(result.success)
        assertEquals(AiFailureReason.ENGINE_ERROR, result.failureReason)
        assertEquals(detail, result.errorDetail)
    }

    @Test
    fun `a server ERROR with no detail leaves errorDetail null, never a fabricated string`() = runTest {
        val client = FakeCoreClient(
            sendResult = { req ->
                JarvisCoreResponse(requestId = req.requestId, status = CoreResponseStatus.ERROR, error = null)
            },
        )
        val engine = RemoteAiEngine(client)

        val result = engine.generate(request())

        assertNull(result.errorDetail)
    }

    private fun okResponse(requestId: String, text: String = "ok") =
        JarvisCoreResponse(requestId = requestId, status = CoreResponseStatus.OK, text = text)

    /** Minimal fake — only [send] is exercised by [RemoteAiEngine.generate], the only method under test here. */
    private inner class FakeCoreClient(
        private val sendResult: ((JarvisCoreRequest) -> JarvisCoreResponse)? = null,
        private val sendThrows: Throwable? = null,
    ) : CoreClient {
        override suspend fun healthCheck(): CoreHealthResult = CoreHealthResult(reachable = true)
        override suspend fun testConnection(): CoreConnectionTestResult = CoreConnectionTestResult(reachable = true)

        override suspend fun send(request: JarvisCoreRequest): JarvisCoreResponse {
            sendThrows?.let { throw it }
            return sendResult?.invoke(request) ?: okResponse(request.requestId)
        }

        override fun stream(request: JarvisCoreRequest): Flow<JarvisCoreStreamChunk> = flow {}
        override fun cancel(requestId: String) = Unit
        override suspend fun publishEvent(event: JarvisEvent): Boolean = true
        override suspend fun describeEndpoint(): String = "http://fake:0"
    }
}
