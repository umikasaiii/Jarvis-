package com.simone.jarvismobile.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the exact scenarios required for the Core integration's mandatory
 * test list: Core online, offline, and — the one easy to get wrong —
 * protocolVersion mismatch falling back rather than being treated as a
 * healthy match.
 */
class CoreStateDecisionTest {

    private val expected = "1"

    @Test
    fun `unreachable is offline regardless of anything else`() {
        assertEquals(
            JarvisCoreState.OFFLINE,
            CoreStateDecision.fromHealthCheck(reachable = false, protocolVersion = expected, expectedProtocolVersion = expected, llmAvailable = true),
        )
    }

    @Test
    fun `reachable with matching protocol and llm available is online`() {
        assertEquals(
            JarvisCoreState.ONLINE,
            CoreStateDecision.fromHealthCheck(reachable = true, protocolVersion = expected, expectedProtocolVersion = expected, llmAvailable = true),
        )
    }

    @Test
    fun `reachable with no protocolVersion reported still counts as online`() {
        // An older/minimal server that omits the field is not automatically
        // treated as incompatible — only an actually-different value is.
        assertEquals(
            JarvisCoreState.ONLINE,
            CoreStateDecision.fromHealthCheck(reachable = true, protocolVersion = null, expectedProtocolVersion = expected, llmAvailable = true),
        )
    }

    @Test
    fun `protocolVersion mismatch degrades rather than staying online`() {
        assertEquals(
            JarvisCoreState.DEGRADED,
            CoreStateDecision.fromHealthCheck(reachable = true, protocolVersion = "2", expectedProtocolVersion = expected, llmAvailable = true),
        )
    }

    @Test
    fun `degraded still counts as remote-usable - a real request still gets attempted`() {
        val state = CoreStateDecision.fromHealthCheck(reachable = true, protocolVersion = "2", expectedProtocolVersion = expected, llmAvailable = true)
        assertEquals(true, state.remoteUsable)
    }

    @Test
    fun `llmAvailable false degrades even with a matching protocolVersion`() {
        assertEquals(
            JarvisCoreState.DEGRADED,
            CoreStateDecision.fromHealthCheck(reachable = true, protocolVersion = expected, expectedProtocolVersion = expected, llmAvailable = false),
        )
    }

    @Test
    fun `llmAvailable unknown (null) does not itself degrade`() {
        assertEquals(
            JarvisCoreState.ONLINE,
            CoreStateDecision.fromHealthCheck(reachable = true, protocolVersion = expected, expectedProtocolVersion = expected, llmAvailable = null),
        )
    }
}
