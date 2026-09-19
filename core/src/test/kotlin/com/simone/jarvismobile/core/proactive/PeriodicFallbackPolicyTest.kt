package com.simone.jarvismobile.core.proactive

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class PeriodicFallbackPolicyTest {

    @Test
    fun `automation service desired off - always offers morning, any time`() {
        assertTrue(
            PeriodicFallbackPolicy.shouldOfferMorningOnPeriodicTick(
                automationServiceDesiredOn = false,
                nowMinuteOfDay = 6 * 60, // 06:00
                configuredFallbackMinuteOfDay = 8 * 60, // 08:00
            ),
        )
    }

    @Test
    fun `automation service desired on, before configured fallback - never sends early`() {
        assertFalse(
            PeriodicFallbackPolicy.shouldOfferMorningOnPeriodicTick(
                automationServiceDesiredOn = true,
                nowMinuteOfDay = 7 * 60 + 59,
                configuredFallbackMinuteOfDay = 8 * 60,
            ),
        )
    }

    @Test
    fun `automation service desired on, exactly at configured fallback - now eligible (the P0 fix)`() {
        assertTrue(
            PeriodicFallbackPolicy.shouldOfferMorningOnPeriodicTick(
                automationServiceDesiredOn = true,
                nowMinuteOfDay = 8 * 60,
                configuredFallbackMinuteOfDay = 8 * 60,
            ),
        )
    }

    @Test
    fun `automation service desired on, well after configured fallback - still eligible (never suppressed forever)`() {
        assertTrue(
            PeriodicFallbackPolicy.shouldOfferMorningOnPeriodicTick(
                automationServiceDesiredOn = true,
                nowMinuteOfDay = 20 * 60,
                configuredFallbackMinuteOfDay = 8 * 60,
            ),
        )
    }
}
