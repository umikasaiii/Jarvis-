package com.simone.jarvismobile.core.promode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProModeCommandsTest {

    @Test
    fun recognisesActivationPhrases() {
        assertEquals(ProModeCommand.ACTIVATE, ProModeCommands.parse("attiva modalità pro"))
        assertEquals(ProModeCommand.ACTIVATE, ProModeCommands.parse("Entra in modalità pro"))
        assertEquals(ProModeCommand.ACTIVATE, ProModeCommands.parse("ehi jarvis, vai in modalità pro"))
    }

    @Test
    fun recognisesDeactivationPhrases() {
        assertEquals(ProModeCommand.DEACTIVATE, ProModeCommands.parse("disattiva modalità pro"))
        assertEquals(ProModeCommand.DEACTIVATE, ProModeCommands.parse("Esci dalla modalità pro"))
        assertEquals(ProModeCommand.DEACTIVATE, ProModeCommands.parse("torna alla modalità normale"))
    }

    @Test
    fun ordinaryConversationAboutProModeIsNotACommand() {
        assertNull(ProModeCommands.parse("attiva la modalità pro tra un minuto"))
        assertNull(ProModeCommands.parse("cos'è la modalità pro?"))
        assertNull(ProModeCommands.parse("non voglio la modalità pro adesso"))
        assertNull(ProModeCommands.parse(""))
    }
}
