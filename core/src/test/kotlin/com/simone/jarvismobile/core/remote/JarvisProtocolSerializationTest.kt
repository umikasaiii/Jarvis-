package com.simone.jarvismobile.core.remote

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Decodes the ACTUAL example payloads published in jarvis-protocol/main
 * (schemas v1.0.0) — copied verbatim from
 * https://github.com/umikasaiii/Jarvis-protocol, examples directory — to prove
 * these DTOs match the real, formalized wire format, not an assumption of it.
 */
class JarvisProtocolSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    // jarvis-protocol/main examples/chat-response.json
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

    // jarvis-protocol/main examples/health-response.json
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

    // jarvis-protocol/main examples/capabilities-response.json
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
    fun `decodes the real chat-response example`() {
        val response = json.decodeFromString(JarvisResponse.serializer(), chatResponseJson)
        assertEquals("8f14e45f-ceea-467e-b7c1-31a0b4a53a3f", response.requestId)
        assertEquals(ResponseStatus.OK, response.status)
        assertEquals(ExecutionTarget.FAST, response.targetUsed)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(8, response.tokensGenerated)
        assertNull(response.error)
    }

    @Test
    fun `decodes the real health-response example`() {
        val health = json.decodeFromString(HealthResponse.serializer(), healthResponseJson)
        assertEquals("online", health.status)
        assertEquals(JARVIS_PROTOCOL_VERSION, health.protocolVersion)
        assertEquals(true, health.llmAvailable)
        assertEquals("fast-fake", health.activeModel)
    }

    @Test
    fun `decodes the real capabilities-response example`() {
        val caps = json.decodeFromString(CapabilitiesResponse.serializer(), capabilitiesResponseJson)
        assertEquals(true, caps.chat)
        assertEquals(true, caps.streaming)
        assertEquals(false, caps.memory)
        assertEquals(false, caps.voice)
        assertEquals(false, caps.actions)
        assertEquals(JARVIS_PROTOCOL_VERSION, caps.protocolVersion)
    }

    // jarvis-protocol/main examples/stream-start.json / stream-chunk.json /
    // stream-done.json / stream-error.json
    @Test
    fun `decodes all four real stream event examples`() {
        val start = json.decodeFromString(
            StreamEvent.serializer(),
            """{"type":"start","requestId":"r1","targetUsed":"FAST"}""",
        )
        assertEquals(StreamEventType.START, start.type)
        assertEquals(ExecutionTarget.FAST, start.targetUsed)
        assertNull(start.content)

        val chunk = json.decodeFromString(
            StreamEvent.serializer(),
            """{"type":"token","requestId":"r1","content":"[fast-fake] "}""",
        )
        assertEquals(StreamEventType.TOKEN, chunk.type)
        assertEquals("[fast-fake] ", chunk.content)

        val done = json.decodeFromString(
            StreamEvent.serializer(),
            """{"type":"done","requestId":"r1","modelUsed":"fast-fake","targetUsed":"FAST","executionTimeMs":842.1,"tokensGenerated":8,"finishReason":"stop"}""",
        )
        assertEquals(StreamEventType.DONE, done.type)
        assertEquals(FinishReason.STOP, done.finishReason)
        assertEquals(8, done.tokensGenerated)

        val error = json.decodeFromString(
            StreamEvent.serializer(),
            """{"type":"error","requestId":"r1","error":"brain-fake backend unavailable"}""",
        )
        assertEquals(StreamEventType.ERROR, error.type)
        assertEquals("brain-fake backend unavailable", error.error)
    }

    @Test
    fun `outgoing JarvisRequest serializes protocolVersion as the exact required string`() {
        val request = JarvisRequest(text = "Ciao JARVIS", requestType = RequestType.CHAT, preferredTarget = ExecutionTarget.AUTO)
        val encoded = json.encodeToString(JarvisRequest.serializer(), request)
        val roundTrip = json.parseToJsonElement(encoded).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("\"1\"", roundTrip["protocolVersion"].toString())
        assertEquals("\"CHAT\"", roundTrip["requestType"].toString())
        assertEquals("\"AUTO\"", roundTrip["preferredTarget"].toString())
        assertEquals("\"Ciao JARVIS\"", roundTrip["text"].toString())
    }

    @Test
    fun `preferredTarget FAST and BRAIN serialize to the exact protocol enum values`() {
        val fast = json.encodeToString(JarvisRequest.serializer(), JarvisRequest(text = "x", preferredTarget = ExecutionTarget.FAST))
        val brain = json.encodeToString(JarvisRequest.serializer(), JarvisRequest(text = "x", preferredTarget = ExecutionTarget.BRAIN))
        assert("\"preferredTarget\":\"FAST\"" in fast)
        assert("\"preferredTarget\":\"BRAIN\"" in brain)
    }
}
