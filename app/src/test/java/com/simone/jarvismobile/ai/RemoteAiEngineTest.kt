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

    private fun okResponse(requestId: String, text: String = "ok") =
        JarvisCoreResponse(requestId = requestId, status = CoreResponseStatus.OK, text = text)

    /** Minimal fake — only [send] is exercised by [RemoteAiEngine.generate], the only method under test here. */
    private class FakeCoreClient(
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
    }
}
