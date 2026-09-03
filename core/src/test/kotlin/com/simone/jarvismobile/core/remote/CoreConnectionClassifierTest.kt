package com.simone.jarvismobile.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreConnectionClassifierTest {

    private fun health(protocolVersion: String = "1", llmAvailable: Boolean = true) = HealthResponse(
        status = "online",
        serverVersion = "0.1.0",
        protocolVersion = protocolVersion,
        uptimeSeconds = 12.0,
        llmAvailable = llmAvailable,
        activeModel = "fast-fake",
        device = "jarvis-pc",
        timestamp = "2026-01-15T10:30:00+00:00",
    )

    @Test
    fun `matching protocol and available llm is ONLINE`() {
        val check = CoreConnectionClassifier.classify(CoreResult.Success(health()), latencyMs = 42)
        assertEquals(CoreConnectionState.ONLINE, check.state)
        assertTrue(check.reachable)
        assertTrue(check.protocolMatches)
        assertEquals(42L, check.latencyMs)
    }

    @Test
    fun `protocol version mismatch is DEGRADED not ONLINE`() {
        val check = CoreConnectionClassifier.classify(CoreResult.Success(health(protocolVersion = "2")), latencyMs = 10)
        assertEquals(CoreConnectionState.DEGRADED, check.state)
        assertTrue(check.reachable) // it did answer
        assertFalse(check.protocolMatches)
    }

    @Test
    fun `llmAvailable false is DEGRADED`() {
        val check = CoreConnectionClassifier.classify(CoreResult.Success(health(llmAvailable = false)), latencyMs = 10)
        assertEquals(CoreConnectionState.DEGRADED, check.state)
    }

    @Test
    fun `network failure is OFFLINE`() {
        val check = CoreConnectionClassifier.classify(CoreResult.Failure.Network("connection refused"), latencyMs = 0)
        assertEquals(CoreConnectionState.OFFLINE, check.state)
        assertFalse(check.reachable)
    }

    @Test
    fun `timeout is OFFLINE`() {
        val check = CoreConnectionClassifier.classify(CoreResult.Failure.Timeout("timed out"), latencyMs = 0)
        assertEquals(CoreConnectionState.OFFLINE, check.state)
    }

    @Test
    fun `an http error from Core is ERROR, not OFFLINE (Core IS reachable)`() {
        val check = CoreConnectionClassifier.classify(
            CoreResult.Failure.Http(500, "internal_error", "internal_error"),
            latencyMs = 8,
        )
        assertEquals(CoreConnectionState.ERROR, check.state)
        assertFalse(check.reachable)
    }

    @Test
    fun `a malformed body is ERROR`() {
        val check = CoreConnectionClassifier.classify(CoreResult.Failure.Malformed("bad json"), latencyMs = 5)
        assertEquals(CoreConnectionState.ERROR, check.state)
    }
}
