package com.simone.jarvismobile.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandMatcherTest {
    @Test
    fun batteryLevelQuestionRunsBatteryTool() {
        val match = CommandMatcher.match("Quanto ho di batteria?")
        assertTrue(match is Match.Run)
        assertEquals("battery_status", (match as Match.Run).call.name)
    }

    @Test
    fun subjectlessChargingFollowUpUsesRecentBatteryContext() {
        val match = CommandMatcher.match(
            utterance = "È in carica in questo momento?",
            recentContext = "JARVIS: Batteria al 93 per cento.",
        )
        assertTrue(match is Match.Run)
        assertEquals("battery_status", (match as Match.Run).call.name)
    }

    @Test
    fun batteryStatementDoesNotRunATool() {
        assertNull(CommandMatcher.match("La batteria si sta caricando"))
    }
}
