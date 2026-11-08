package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BETA 3 — the Attività data model as the Agenda.md round trip sees it (JVM, no Android). */
class TaskLifecycleTest {
    private val day = LocalDate.of(2026, 11, 10)

    private fun roundTrip(entries: List<AgendaEntry>) = Agenda.parseFile(Agenda.renderFile(entries))

    @Test fun createdTaskSurvivesReload() {
        val t = AgendaEntry(date = day, time = LocalTime.of(18, 0), text = "Controllare l'assicurazione", alerts = listOf(ReminderAlert(ReminderAlertType.AT_TIME)))
        val back = roundTrip(listOf(t)).single()
        assertEquals(t, back)
    }

    @Test fun listStarNotesAndOrderSurviveReload() {
        val t = AgendaEntry(date = null, text = "Latte", list = "Spesa", starred = true, notes = "intero", order = 3)
        val back = roundTrip(listOf(t)).single()
        assertEquals("Spesa", back.list); assertTrue(back.starred); assertEquals("intero", back.notes); assertEquals(3, back.order)
        assertNull(back.date)
    }

    @Test fun movingToAnotherListKeepsIdentity() {
        val t = AgendaEntry(date = null, text = "Pane", list = "Personale")
        val moved = t.copy(list = "Spesa").copy(id = t.id)
        val back = roundTrip(listOf(moved)).single()
        assertEquals(t.id, back.id); assertEquals("Spesa", back.list)
    }

    @Test fun completionStampsAndReopenClearsIt() {
        val t = AgendaEntry(date = day, text = "Chiamare")
        val done = t.copy(done = true, completedAt = day)
        val back = roundTrip(listOf(done)).single()
        assertTrue(back.done); assertEquals(day, back.completedAt)
        val reopened = back.copy(done = false, completedAt = null)
        val again = roundTrip(listOf(reopened)).single()
        assertFalse(again.done); assertNull(again.completedAt)
    }

    @Test fun subTaskKeepsItsParentAndList() {
        val parent = AgendaEntry(date = null, text = "Viaggio", list = "Vacanze")
        val child = AgendaEntry(date = null, text = "Prenotare hotel", list = "Vacanze", parentId = parent.id, order = 1)
        val back = roundTrip(listOf(parent, child))
        assertEquals(2, back.size)
        assertEquals(parent.id, back.single { it.text == "Prenotare hotel" }.parentId)
    }

    @Test fun reloadDoesNotDuplicateOrLoseEntries() {
        val all = (1..20).map { AgendaEntry(date = day.plusDays(it.toLong() % 3), text = "Attività $it", list = if (it % 2 == 0) "A" else "B") }
        val once = roundTrip(all)
        val twice = roundTrip(once)
        assertEquals(all.size, once.size)
        assertEquals(once.map { it.id }.toSet(), twice.map { it.id }.toSet())
    }

    @Test fun nonTaskLinesDoNotCrashTheParser() {
        val text = Agenda.renderFile(listOf(AgendaEntry(date = day, text = "Vera"))) + "\nriga libera scritta a mano\n- [ ] ??? rotta\n"
        assertEquals(1, Agenda.parseFile(text).size)
    }

    // --- notifications ---------------------------------------------------
    @Test fun atTimeAlertFiresAtTheDueMinute() {
        val t = AgendaEntry(date = day, time = LocalTime.of(18, 0), text = "x")
        assertEquals(LocalDateTime.of(2026, 11, 10, 18, 0), ReminderSchedule.triggerAt(t, ReminderAlert(ReminderAlertType.AT_TIME)))
    }

    @Test fun morningOfUsesTheConfiguredMorningHour() {
        val t = AgendaEntry(date = day, text = "x")
        assertEquals(LocalDateTime.of(2026, 11, 10, 7, 0), ReminderSchedule.triggerAt(t, ReminderAlert(ReminderAlertType.MORNING_OF), LocalTime.of(7, 0)))
    }

    @Test fun anUndatedTaskNeverFiresARelativeAlert() {
        val t = AgendaEntry(date = null, text = "x")
        assertNull(ReminderSchedule.triggerAt(t, ReminderAlert(ReminderAlertType.AT_TIME)))
        assertNull(ReminderSchedule.triggerAt(t, ReminderAlert(ReminderAlertType.MORNING_OF)))
    }

    @Test fun timeEditedOntoAnUndatedTaskNowProducesAnAlert() {
        val before = AgendaEntry(date = null, text = "Chiamare")
        val alerts = DueAlerts.afterDueEdit(before.alerts, day, LocalTime.of(9, 30))
        val edited = before.copy(date = day, time = LocalTime.of(9, 30), alerts = alerts)
        assertNotNull(ReminderSchedule.triggerAt(edited, edited.alerts.single()))
    }

    @Test fun sortingPutsUndatedTasksLast() {
        val sorted = Agenda.sorted(listOf(AgendaEntry(date = null, text = "a"), AgendaEntry(date = day, text = "b")))
        assertEquals(listOf("b", "a"), sorted.map { it.text })
    }
}
