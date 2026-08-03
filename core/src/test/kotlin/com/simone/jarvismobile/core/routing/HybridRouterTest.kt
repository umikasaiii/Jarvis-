package com.simone.jarvismobile.core.routing

import com.simone.jarvismobile.core.state.RouteTarget
import com.simone.jarvismobile.core.tools.SensitivityLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridRouterTest {

    private val router = HybridRouter()

    private fun ctx(
        online: Boolean = true,
        pc: Boolean = true,
        ha: Boolean = true,
        profile: PrivacyProfile = PrivacyProfile.PREFER_PC_TRUSTED_NETWORK,
        memory: Boolean = false,
        complexity: Double = 0.9,
        sensitivity: SensitivityLevel = SensitivityLevel.PERSONAL,
    ) = RoutingContext(online, pc, ha, profile, memory, complexity, sensitivity)

    @Test
    fun `deterministic command bypasses the model`() {
        val d = router.route("che ore sono", ctx())
        assertEquals(RouteTarget.DETERMINISTIC, d.target)
        assertTrue(d.reason.startsWith("deterministic:get_current_time"))
        assertEquals(MemorySharing.NONE, d.memorySharing)
    }

    @Test
    fun `home assistant intent routes to home assistant when reachable`() {
        val d = router.route("accendi le luci del salotto", ctx())
        assertEquals(RouteTarget.HOME_ASSISTANT, d.target)
    }

    @Test
    fun `home assistant intent falls back to local when unreachable`() {
        val d = router.route("accendi le luci del salotto", ctx(ha = false))
        assertEquals(RouteTarget.LOCAL, d.target)
    }

    @Test
    fun `complex request offloads to pc when allowed and reachable`() {
        val d = router.route("scrivimi un saggio dettagliato sulla fisica quantistica", ctx())
        assertEquals(RouteTarget.REMOTE_PC, d.target)
        assertEquals(RouteTarget.LOCAL, d.fallback)
    }

    @Test
    fun `device only profile never leaves the device`() {
        val d = router.route("scrivimi un saggio dettagliato", ctx(profile = PrivacyProfile.DEVICE_ONLY))
        assertEquals(RouteTarget.LOCAL, d.target)
        assertEquals("device_only_profile", d.reason)
    }

    @Test
    fun `sensitive content stays local even if pc is available`() {
        val d = router.route(
            "annota i miei dati medici",
            ctx(memory = true, sensitivity = SensitivityLevel.SENSITIVE),
        )
        assertEquals(RouteTarget.LOCAL, d.target)
    }

    @Test
    fun `offline complex request falls back to local`() {
        val d = router.route("scrivimi un saggio dettagliato", ctx(online = false, pc = false))
        assertEquals(RouteTarget.LOCAL, d.target)
        assertEquals("offline_local", d.reason)
    }

    @Test
    fun `remote memory sharing is capped to selected fragments`() {
        val d = router.route("ricordami cosa ho deciso sul progetto casa", ctx(memory = true))
        assertEquals(RouteTarget.REMOTE_PC, d.target)
        assertEquals(MemorySharing.SELECTED_FRAGMENTS_ONLY, d.memorySharing)
    }

    @Test
    fun `simple request stays local even when pc available`() {
        val d = router.route("come stai", ctx(complexity = 0.1))
        assertEquals(RouteTarget.LOCAL, d.target)
        assertEquals("simple_local", d.reason)
    }
}
