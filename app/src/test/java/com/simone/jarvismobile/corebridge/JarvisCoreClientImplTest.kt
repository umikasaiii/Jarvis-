package com.simone.jarvismobile.corebridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-conformance tests for the JARVIS Core wire models
 * ([CoreModels.kt]) and the real SSE event -> [JarvisCoreStreamChunk]
 * mapping ([mapSseEventToChunk], extracted out of [JarvisCoreClientImpl] so
 * it is testable without an `OkHttpClient`/`SettingsRepository`/`Context`).
 *
 * The example JSON bodies below are copied verbatim from
 * jarvis-protocol/main v1.0.0 (github.com/umikasaiii/Jarvis-protocol,
 * examples directory, *.json files) — the formalized source of truth for
 * the Android<->Core wire format — so these tests prove conformance
 * against the real, published contract, not an assumption of it.
 */
class JarvisCoreClientImplTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- outgoing request -------------------------------------------------

    @Test
    fun `protocolVersion always encodes as the literal string '1', never a number`() {
        val request = JarvisCoreRequest(requestId = "r1", requestType = CoreRequestType.CHAT, text = "ciao")
        val encoded = json.encodeToString(JarvisCoreRequest.serializer(), request)
        val obj = json.parseToJsonElement(encoded).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("\"1\"", obj["protocolVersion"].toString())
    }

    @Test
    fun `preferredTarget FAST and BRAIN encode to the exact protocol enum values`() {
        val fast = json.encodeToString(
            JarvisCoreRequest.serializer(),
            JarvisCoreRequest(requestId = "r1", requestType = CoreRequestType.CHAT, text = "x", preferredTarget = CoreExecutionTarget.FAST),
        )
        val brain = json.encodeToString(
            JarvisCoreRequest.serializer(),
            JarvisCoreRequest(requestId = "r1", requestType = CoreRequestType.CHAT, text = "x", preferredTarget = CoreExecutionTarget.BRAIN),
        )
        assertTrue("\"preferredTarget\":\"FAST\"" in fast)
        assertTrue("\"preferredTarget\":\"BRAIN\"" in brain)
    }

    /**
     * jarvis-protocol/main v1.1.0 (§ FASE SUCCESSIVA — integrazione Motore
     * Conversazionale): `systemPrompt` must round-trip verbatim when set, so
     * `JarvisBrain`'s persona/protocol-block/tool-catalog actually reaches
     * Core instead of being silently dropped the way it always was before
     * this field existed.
     */
    @Test
    fun `systemPrompt encodes verbatim when present`() {
        val request = JarvisCoreRequest(
            requestId = "r1",
            requestType = CoreRequestType.CHAT,
            text = "ciao",
            systemPrompt = "Sei JARVIS. Rispondi solo in JSON.",
        )
        val encoded = json.encodeToString(JarvisCoreRequest.serializer(), request)
        assertTrue("\"systemPrompt\":\"Sei JARVIS. Rispondi solo in JSON.\"" in encoded)
    }

    @Test
    fun `systemPrompt absent by default, matching pre-1_1_0 clients`() {
        val request = JarvisCoreRequest(requestId = "r1", requestType = CoreRequestType.CHAT, text = "ciao")
        assertNull(request.systemPrompt)
    }

    // --- real jarvis-protocol/main examples --------------------------------

    // examples/chat-response.json
    private val chatResponseJson = """
        {
          "requestId": "8f14e45f-ceea-467e-b7c1-31a0b4a53a3f",
          "status": "OK",
          "text": "[fast-fake] echo: Ciao JARVIS, che tempo fa oggi? ",
          "modelUsed": "fast-fake",
          "targetUsed": "FAST",
          "executionTimeMs": 12.4,
          "tokensGenerated": 8,
          "finishReason": "stop",
          "warnings": [],
          "error": null
        }
    """.trimIndent()

    @Test
    fun `decodes the real chat-response example, including the float executionTimeMs`() {
        val response = json.decodeFromString(JarvisCoreResponse.serializer(), chatResponseJson)
        assertEquals("8f14e45f-ceea-467e-b7c1-31a0b4a53a3f", response.requestId)
        assertEquals(CoreResponseStatus.OK, response.status)
        assertEquals(CoreExecutionTarget.FAST, response.targetUsed)
        assertEquals(12.4, response.executionTimeMs)
        assertEquals("stop", response.finishReason)
        assertNull(response.error)
    }

    // examples/health-response.json
    private val healthResponseJson = """
        {
          "status": "online",
          "serverVersion": "0.1.0",
          "protocolVersion": "1",
          "uptimeSeconds": 4213.7,
          "llmAvailable": true,
          "activeModel": "fast-fake",
          "device": "jarvis-pc",
          "timestamp": "2026-01-15T10:30:00.123456+00:00"
        }
    """.trimIndent()

    @Test
    fun `decodes the real health-response example`() {
        val health = json.decodeFromString(CoreHealthBody.serializer(), healthResponseJson)
        assertEquals("online", health.status)
        assertEquals(CORE_PROTOCOL_VERSION, health.protocolVersion)
        assertTrue(health.llmAvailable)
        assertEquals("fast-fake", health.activeModel)
    }

    // examples/capabilities-response.json
    private val capabilitiesResponseJson = """
        {
          "chat": true,
          "streaming": true,
          "fastModel": true,
          "brainModel": true,
          "memory": false,
          "rag": false,
          "voice": false,
          "vision": false,
          "contextEngine": false,
          "actions": false,
          "protocolVersion": "1"
        }
    """.trimIndent()

    @Test
    fun `decodes the real capabilities-response example`() {
        val caps = json.decodeFromString(CoreCapabilitiesBody.serializer(), capabilitiesResponseJson)
        assertTrue(caps.chat)
        assertTrue(caps.streaming)
        assertTrue(!caps.memory)
        assertTrue(!caps.voice)
        assertTrue(!caps.actions)
    }

    @Test
    fun `decodes all four real stream event examples`() {
        val start = json.decodeFromString(CoreSseEvent.serializer(), """{"type":"start","requestId":"r1","targetUsed":"FAST"}""")
        assertEquals(CoreSseEventType.START, start.type)

        val token = json.decodeFromString(CoreSseEvent.serializer(), """{"type":"token","requestId":"r1","content":"[fast-fake] "}""")
        assertEquals(CoreSseEventType.TOKEN, token.type)
        assertEquals("[fast-fake] ", token.content)

        val done = json.decodeFromString(
            CoreSseEvent.serializer(),
            """{"type":"done","requestId":"r1","modelUsed":"fast-fake","targetUsed":"FAST","executionTimeMs":842.1,"tokensGenerated":8,"finishReason":"stop"}""",
        )
        assertEquals(CoreSseEventType.DONE, done.type)
        assertEquals(842.1, done.executionTimeMs)

        val error = json.decodeFromString(CoreSseEvent.serializer(), """{"type":"error","requestId":"r1","error":"brain-fake backend unavailable"}""")
        assertEquals(CoreSseEventType.ERROR, error.type)
        assertEquals("brain-fake backend unavailable", error.error)
    }

    // --- SSE event -> app-internal chunk mapping (mapSseEventToChunk) ------

    @Test
    fun `start event maps to nothing - there is no delta to relay yet`() {
        assertNull(mapSseEventToChunk(CoreSseEvent(type = CoreSseEventType.START, requestId = "r1", targetUsed = CoreExecutionTarget.FAST)))
    }

    @Test
    fun `token event maps to a non-terminal chunk carrying its content as delta`() {
        val chunk = mapSseEventToChunk(CoreSseEvent(type = CoreSseEventType.TOKEN, requestId = "r1", content = "Ciao"))
        assertEquals("r1", chunk?.requestId)
        assertEquals("Ciao", chunk?.delta)
        assertEquals(false, chunk?.done)
        assertNull(chunk?.error)
    }

    @Test
    fun `done event maps to a terminal chunk with no error`() {
        val chunk = mapSseEventToChunk(CoreSseEvent(type = CoreSseEventType.DONE, requestId = "r1", finishReason = "stop"))
        assertEquals(true, chunk?.done)
        assertNull(chunk?.error)
    }

    @Test
    fun `error event maps to a terminal chunk carrying the error message`() {
        val chunk = mapSseEventToChunk(CoreSseEvent(type = CoreSseEventType.ERROR, requestId = "r1", error = "brain-fake backend unavailable"))
        assertEquals(true, chunk?.done)
        assertEquals("brain-fake backend unavailable", chunk?.error)
    }

    @Test
    fun `error event with no message still terminates the stream with a non-null error`() {
        val chunk = mapSseEventToChunk(CoreSseEvent(type = CoreSseEventType.ERROR, requestId = "r1"))
        assertEquals(true, chunk?.done)
        assertTrue(chunk?.error?.isNotBlank() == true)
    }
}
