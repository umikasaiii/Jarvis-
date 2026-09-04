package com.simone.jarvismobile.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class AiRoutingHeuristicTest {

    private fun prefs(
        remoteAiEnabled: Boolean = true,
        coreState: JarvisCoreState = JarvisCoreState.ONLINE,
        coreHasBrainModel: Boolean = true,
        allowRemote: Boolean = true,
    ) = AiRoutingPreferences(remoteAiEnabled, coreState, coreHasBrainModel, allowRemote)

    @Test
    fun `Core disabled always stays local regardless of request type`() {
        val disabled = prefs(remoteAiEnabled = false)
        for (type in AiRequestType.entries) {
            assertEquals(AiExecutionTarget.LOCAL, AiRoutingHeuristic.decide(type, disabled).target)
        }
    }

    @Test
    fun `Core offline falls back to local for every request type`() {
        val offline = prefs(coreState = JarvisCoreState.OFFLINE)
        for (type in AiRequestType.entries) {
            assertEquals(AiExecutionTarget.LOCAL, AiRoutingHeuristic.decide(type, offline).target)
        }
    }

    @Test
    fun `Core degraded still counts as remote-usable`() {
        val degraded = prefs(coreState = JarvisCoreState.DEGRADED)
        assertEquals(AiExecutionTarget.REMOTE_FAST, AiRoutingHeuristic.decide(AiRequestType.CHAT, degraded).target)
    }

    @Test
    fun `command always stays local even with Core online`() {
        assertEquals(AiExecutionTarget.LOCAL, AiRoutingHeuristic.decide(AiRequestType.COMMAND, prefs()).target)
    }

    @Test
    fun `proactive always stays local even with Core online`() {
        assertEquals(AiExecutionTarget.LOCAL, AiRoutingHeuristic.decide(AiRequestType.PROACTIVE, prefs()).target)
    }

    @Test
    fun `chat routes to remote fast when Core online`() {
        assertEquals(AiExecutionTarget.REMOTE_FAST, AiRoutingHeuristic.decide(AiRequestType.CHAT, prefs()).target)
    }

    /**
     * Pins a real, easy-to-hit misconfiguration: "Instrada le richieste al
     * PC" (`remoteAiEnabled`) on, "Testa connessione" reporting Online (a
     * one-off probe independent of `coreEnabled`, see `CoreClient.testConnection`),
     * but "Abilita Core" (`coreEnabled`) itself still off — `CoreConnectionManager`
     * then never leaves [JarvisCoreState.DISABLED] (its heartbeat/`ensureFresh`
     * both gate on `coreEnabled` first), so ordinary chat silently stays local
     * with zero requests ever reaching Core, despite both settings screen
     * toggles the user actually flips reading as "on".
     */
    @Test
    fun `remoteAiEnabled on but Core state still disabled stays local with a distinct reason`() {
        val instradaOnButCoreOff = prefs(remoteAiEnabled = true, coreState = JarvisCoreState.DISABLED)
        val decision = AiRoutingHeuristic.decide(AiRequestType.CHAT, instradaOnButCoreOff)
        assertEquals(AiExecutionTarget.LOCAL, decision.target)
        assertEquals("core_disabled", decision.reason)
    }

    @Test
    fun `tool routes to remote fast when Core online`() {
        assertEquals(AiExecutionTarget.REMOTE_FAST, AiRoutingHeuristic.decide(AiRequestType.TOOL, prefs()).target)
    }

    @Test
    fun `complex routes to remote brain when a brain model is available`() {
        assertEquals(AiExecutionTarget.REMOTE_BRAIN, AiRoutingHeuristic.decide(AiRequestType.COMPLEX, prefs()).target)
    }

    @Test
    fun `memory routes to remote brain when a brain model is available`() {
        assertEquals(AiExecutionTarget.REMOTE_BRAIN, AiRoutingHeuristic.decide(AiRequestType.MEMORY, prefs()).target)
    }

    @Test
    fun `complex falls back to remote fast when no brain model is loaded`() {
        val noBrain = prefs(coreHasBrainModel = false)
        assertEquals(AiExecutionTarget.REMOTE_FAST, AiRoutingHeuristic.decide(AiRequestType.COMPLEX, noBrain).target)
    }

    @Test
    fun `caller can forbid remote even when Core is online`() {
        val forbidden = prefs(allowRemote = false)
        assertEquals(AiExecutionTarget.LOCAL, AiRoutingHeuristic.decide(AiRequestType.CHAT, forbidden).target)
    }

    @Test
    fun `reason string never empty and stays a technical slug`() {
        for (type in AiRequestType.entries) {
            val reason = AiRoutingHeuristic.decide(type, prefs()).reason
            assertEquals(reason, reason.lowercase())
        }
    }
}
