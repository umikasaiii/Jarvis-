package com.simone.jarvismobile.automation

import com.simone.jarvismobile.core.automation.Action
import com.simone.jarvismobile.core.automation.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DeferredCommandTest {

    private val now = LocalDateTime.of(2026, 8, 8, 9, 0)

    @Test
    fun `a timed device command becomes a one-shot rule, not an immediate action`() {
        val rule = DeferredCommand.parse("alle 11.45 accendi la torcia", now)!!
        assertEquals(Trigger.Once(LocalDateTime.of(2026, 8, 8, 11, 45)), rule.trigger)
        assertTrue(rule.action is Action.Tool)
        assertEquals("accendi la torcia", (rule.action as Action.Tool).command)
    }

    @Test
    fun `a media command with a time is deferrable`() {
        val rule = DeferredCommand.parse("alle 22:00 metti in pausa la musica", now)
        assertTrue(rule != null && rule.action is Action.Tool)
    }

    @Test
    fun `a command with no time runs now rather than becoming a rule`() {
        assertNull(DeferredCommand.parse("accendi la torcia", now))
    }

    @Test
    fun `an appointment is not a device command`() {
        // "dentista" maps to no tool, so this stays an appointment for the agenda.
        assertNull(DeferredCommand.parse("domani alle 15 dentista", now))
    }

    @Test
    fun `a timed query is not deferred - it is not on the eligible tool set`() {
        // "che ore sono" matches a tool, but get_time is a query, not a device
        // action worth scheduling.
        assertNull(DeferredCommand.parse("alle 16 che ore sono", now))
    }

    @Test
    fun `a recurring phrase is left to AutomationPhrase`() {
        assertNull(DeferredCommand.parse("ogni giorno alle 8 accendi la torcia", now))
    }

    @Test
    fun `a time already past today rolls to tomorrow`() {
        val rule = DeferredCommand.parse("alle 8 accendi la torcia", now)!!
        assertEquals(Trigger.Once(LocalDateTime.of(2026, 8, 9, 8, 0)), rule.trigger)
    }
}
