package com.simone.jarvismobile.core.proactive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.2. Pure tests for
 * the gate that closes the real device root cause: a post-delivery content
 * refresh (`ProactiveManager.refreshMorningDigestNotification`, called by
 * `MorningRefreshWorker`) must never act unless today's occurrence is
 * genuinely `DELIVERED` — never assumed, always verified.
 */
class MorningRefreshGateTest {

    @Test
    fun `refresh is allowed only when the occurrence is genuinely DELIVERED`() {
        assertTrue(MorningRefreshGate.shouldRefresh(ProactiveOccurrenceState.DELIVERED))
    }

    @Test
    fun `refresh is refused when the occurrence was never claimed at all`() {
        assertFalse(MorningRefreshGate.shouldRefresh(null))
    }

    @Test
    fun `refresh is refused for every non-terminal or non-delivered state`() {
        val nonDelivered = listOf(
            ProactiveOccurrenceState.CLAIMED,
            ProactiveOccurrenceState.GENERATED,
            ProactiveOccurrenceState.DELIVERY_PENDING,
            ProactiveOccurrenceState.FAILED_RETRYABLE,
            ProactiveOccurrenceState.FAILED_FINAL,
        )
        nonDelivered.forEach { state ->
            assertFalse(
                MorningRefreshGate.shouldRefresh(state),
                "MorningRefreshGate.shouldRefresh($state) must be false - only DELIVERED is a real delivery to refresh",
            )
        }
    }
}
