package com.simone.jarvismobile.core.automation

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationTest {

    // --- the Markdown file --------------------------------------------------

    @Test
    fun `a rule survives a round trip through the file`() {
        val rules = listOf(
            Automation(
                id = "a1b2c3d4",
                name = "vitamine",
                trigger = Trigger.TimeOfDay(LocalTime.of(8, 0)),
                action = Action.Notify("prendi le vitamine"),
            ),
            Automation(
                id = "deadbeef",
                name = "palestra",
                enabled = false,
                trigger = Trigger.TimeOfDay(LocalTime.of(19, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
                action = Action.Speak("è ora di andare in palestra"),
            ),
            Automation(
                id = "cafe0001",
                name = "batteria",
                trigger = Trigger.BatteryBelow(20),
                action = Action.Notify("metti in carica"),
            ),
        )
        val back = AutomationCodec.parseFile(AutomationCodec.renderFile(rules))
        assertEquals(rules.size, back.size)
        rules.zip(back).forEach { (a, b) ->
            assertEquals(a.id, b.id)
            assertEquals(a.enabled, b.enabled)
            assertEquals(a.trigger, b.trigger)
            assertEquals(a.action, b.action)
        }
    }

    @Test
    fun `the written form is a readable sentence`() {
        val rule = Automation(
            id = "a1b2c3d4",
            name = "vitamine",
            trigger = Trigger.TimeOfDay(LocalTime.of(8, 0)),
            action = Action.Notify("prendi le vitamine"),
        )
        assertEquals(
            "- [x] ogni giorno 08:00 — notifica: prendi le vitamine {#a1b2c3d4}",
            rule.toMarkdown(),
        )
    }

    @Test
    fun `an id survives the rule being edited by hand`() {
        val edited = AutomationCodec.parseLine(
            "- [ ] ogni mer 07:15 — voce: buongiorno Simone {#keepme01}",
        )!!
        assertEquals("keepme01", edited.id)
        assertTrue(!edited.enabled)
        assertEquals(Trigger.TimeOfDay(LocalTime.of(7, 15), setOf(DayOfWeek.WEDNESDAY)), edited.trigger)
        assertEquals(Action.Speak("buongiorno Simone"), edited.action)
    }

    @Test
    fun `a line that is not a rule is ignored rather than half-parsed`() {
        assertNull(AutomationCodec.parseLine("# Automazioni di JARVIS"))
        assertNull(AutomationCodec.parseLine("- [x] ogni giorno 08:00 — qualcosa di ignoto"))
        assertNull(AutomationCodec.parseLine("- [x] mai — notifica: ciao"))
        // 25:00 is not a time.
        assertNull(AutomationCodec.parseLine("- [x] ogni giorno 25:00 — notifica: ciao"))
    }

    // --- the Italian sentence ----------------------------------------------

    @Test
    fun `a daily rule is understood`() {
        val a = AutomationPhrase.parse("ogni giorno alle 8 ricordami di prendere le vitamine")!!
        assertEquals(Trigger.TimeOfDay(LocalTime.of(8, 0)), a.trigger)
        assertEquals(Action.Notify("prendere le vitamine"), a.action)
    }

    @Test
    fun `weekdays with accents are matched`() {
        // The whole reason this is spelled out: Java's \b would never match
        // "lunedì" or "venerdì".
        val a = AutomationPhrase.parse("ogni lunedì e venerdì alle 19:30 dimmi di andare in palestra")!!
        val trigger = a.trigger as Trigger.TimeOfDay
        assertEquals(LocalTime.of(19, 30), trigger.time)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), trigger.days)
        assertTrue(a.action is Action.Speak, a.action.toString())
    }

    @Test
    fun `a battery condition is understood`() {
        val a = AutomationPhrase.parse("quando la batteria scende sotto il 20% avvisami")!!
        assertEquals(Trigger.BatteryBelow(20), a.trigger)
    }

    @Test
    fun `plugging in the charger is a condition`() {
        val a = AutomationPhrase.parse("quando metto in carica dimmi buonanotte")!!
        assertEquals(Trigger.ChargingStarted, a.trigger)
        assertEquals(Action.Speak("buonanotte"), a.action)
    }

    @Test
    fun `an agenda action is recognised`() {
        val a = AutomationPhrase.parse("ogni lunedì alle 9 aggiungi in agenda riunione settimanale")!!
        assertTrue(a.action is Action.AddAgenda, a.action.toString())
    }

    /**
     * The distinction the whole parser exists for. A one-off appointment must
     * stay an appointment: turning it into a daily rule would have JARVIS
     * repeating it every day forever.
     */
    @Test
    fun `a one-off appointment is not an automation`() {
        assertNull(AutomationPhrase.parse("domani alle 15 ho il dentista"))
        assertNull(AutomationPhrase.parse("venerdì alle 9 revisione auto"))
        assertNull(AutomationPhrase.parse("ricordami di comprare il pane"))
        assertNull(AutomationPhrase.parse("che ore sono?"))
        assertNull(AutomationPhrase.parse(""))
    }

    @Test
    fun `a recurring phrase with no time is refused rather than guessed`() {
        assertNull(AutomationPhrase.parse("ogni giorno ricordami di bere"))
    }

    @Test
    fun `a parsed rule can be written and read back unchanged`() {
        val a = AutomationPhrase.parse("ogni giorno alle 7:45 dimmi di uscire di casa")!!
        val back = AutomationCodec.parseLine(a.toMarkdown())!!
        assertEquals(a.trigger, back.trigger)
        assertEquals(a.action, back.action)
        assertEquals(a.id, back.id)
    }

    @Test
    fun `the description reads as one line`() {
        val a = Automation(
            name = "x",
            trigger = Trigger.BatteryBelow(15),
            action = Action.Notify("metti in carica"),
        )
        assertEquals("batteria < 15% → notifica: metti in carica", AutomationCodec.describe(a))
    }
}
