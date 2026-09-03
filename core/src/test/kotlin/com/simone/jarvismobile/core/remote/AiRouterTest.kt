package com.simone.jarvismobile.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals

class AiRouterTest {

    private fun input(
        coreEnabled: Boolean = true,
        coreState: CoreConnectionState = CoreConnectionState.ONLINE,
        networkAvailable: Boolean = true,
        needsReasoning: Boolean = false,
        remoteAlreadyFailedThisTurn: Boolean = false,
    ) = AiRoutingInput(coreEnabled, coreState, networkAvailable, needsReasoning, remoteAlreadyFailedThisTurn)

    @Test
    fun `core disabled stays local even when everything else is fine`() {
        assertEquals(AiTarget.LOCAL, AiRouter.decide(input(coreEnabled = false, needsReasoning = true)))
    }

    @Test
    fun `no network falls back to local`() {
        assertEquals(AiTarget.LOCAL, AiRouter.decide(input(networkAvailable = false)))
    }

    @Test
    fun `core offline stays local`() {
        assertEquals(AiTarget.LOCAL, AiRouter.decide(input(coreState = CoreConnectionState.OFFLINE)))
    }

    @Test
    fun `core degraded stays local (protocol mismatch or llm unavailable is not trustworthy)`() {
        assertEquals(AiTarget.LOCAL, AiRouter.decide(input(coreState = CoreConnectionState.DEGRADED)))
    }

    @Test
    fun `core connecting stays local (not proven reachable yet)`() {
        assertEquals(AiTarget.LOCAL, AiRouter.decide(input(coreState = CoreConnectionState.CONNECTING)))
    }

    @Test
    fun `core error state stays local`() {
        assertEquals(AiTarget.LOCAL, AiRouter.decide(input(coreState = CoreConnectionState.ERROR)))
    }

    @Test
    fun `a remote failure already seen this turn forces local, never a second remote try`() {
        assertEquals(
            AiTarget.LOCAL,
            AiRouter.decide(input(remoteAlreadyFailedThisTurn = true, needsReasoning = false)),
        )
        assertEquals(
            AiTarget.LOCAL,
            AiRouter.decide(input(remoteAlreadyFailedThisTurn = true, needsReasoning = true)),
        )
    }

    @Test
    fun `online simple request routes to REMOTE_FAST`() {
        assertEquals(AiTarget.REMOTE_FAST, AiRouter.decide(input(needsReasoning = false)))
    }

    @Test
    fun `online complex request routes to REMOTE_BRAIN`() {
        assertEquals(AiTarget.REMOTE_BRAIN, AiRouter.decide(input(needsReasoning = true)))
    }
}
