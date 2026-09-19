package com.simone.jarvismobile.core.proactive

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class MorningWindowPolicyTest {

    @Test
    fun `before earliest hour - not within window`() {
        assertFalse(MorningWindowPolicy.isWithinWindow(4))
        assertFalse(MorningWindowPolicy.isWithinWindow(0))
    }

    @Test
    fun `earliest hour itself - within window (inclusive)`() {
        assertTrue(MorningWindowPolicy.isWithinWindow(5))
    }

    @Test
    fun `just before cutoff - within window`() {
        assertTrue(MorningWindowPolicy.isWithinWindow(11))
    }

    @Test
    fun `cutoff hour itself - not within window (exclusive)`() {
        assertFalse(MorningWindowPolicy.isWithinWindow(12))
    }

    @Test
    fun `well after cutoff - not within window`() {
        assertFalse(MorningWindowPolicy.isWithinWindow(18))
        assertFalse(MorningWindowPolicy.isWithinWindow(23))
    }
}
