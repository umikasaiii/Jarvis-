package com.simone.jarvismobile.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelLoadAdvisorTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun plentyOfMemoryIsOk() {
        assertEquals(LoadAdviceLevel.OK, ModelLoadAdvisor.assess(3 * gib, 6 * gib, false).level)
    }

    @Test fun tightMemoryWarnsButDoesNotBlock() {
        val a = ModelLoadAdvisor.assess(3 * gib, 3 * gib, false)
        assertEquals(LoadAdviceLevel.TIGHT, a.level); assertNotNull(a.message)
        assertEquals(LoadAdviceLevel.TIGHT, ModelLoadAdvisor.assess(1 * gib, 8 * gib, true).level)
    }

    @Test fun hopelessMemoryIsRefusedWithAClearMessage() {
        val a = ModelLoadAdvisor.assess(4 * gib, 1 * gib, true)
        assertEquals(LoadAdviceLevel.INSUFFICIENT, a.level)
        assertTrue("Memoria libera insufficiente" in a.message!!)
    }

    @Test fun unknownFiguresNeverBlock() {
        assertEquals(LoadAdviceLevel.OK, ModelLoadAdvisor.assess(0, 1, false).level)
        assertNull(ModelLoadAdvisor.assess(3 * gib, 0, false).message)
    }
}
