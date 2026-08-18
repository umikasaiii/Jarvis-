package com.simone.jarvismobile.core.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupertonicQualityTest {

    @Test
    fun stepCountsIncreaseWithQuality() {
        assertTrue(SupertonicQuality.FAST.numSteps < SupertonicQuality.BALANCED.numSteps)
        assertTrue(SupertonicQuality.BALANCED.numSteps < SupertonicQuality.QUALITY.numSteps)
    }

    @Test
    fun balancedIsTheSpecifiedDefaultStepCount() {
        // Pinned to the requested default (§ "Impostazioni iniziali": numSteps = 8).
        assertEquals(8, SupertonicQuality.BALANCED.numSteps)
    }

    @Test
    fun everyPresetHasAtLeastOneStep() {
        SupertonicQuality.entries.forEach { assertTrue(it.numSteps > 0) }
    }
}
