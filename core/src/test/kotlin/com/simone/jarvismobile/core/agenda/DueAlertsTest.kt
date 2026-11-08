package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class DueAlertsTest {
    private val d = LocalDate.of(2026, 11, 9)

    @Test fun addingADateToAnUndatedTaskCreatesTheMorningAlert() =
        assertEquals(listOf(ReminderAlert(ReminderAlertType.MORNING_OF)), DueAlerts.afterDueEdit(emptyList(), d, null))

    @Test fun addingATimeCreatesAtTimeAlert() =
        assertEquals(listOf(ReminderAlert(ReminderAlertType.AT_TIME)), DueAlerts.afterDueEdit(emptyList(), d, LocalTime.of(18, 0)))

    @Test fun existingUserChoiceIsKept() {
        val mine = listOf(ReminderAlert(ReminderAlertType.MORNING_OF))
        assertEquals(mine, DueAlerts.afterDueEdit(mine, d, LocalTime.of(9, 0)))
    }

    @Test fun removingTheDateDropsAlerts() =
        assertEquals(emptyList(), DueAlerts.afterDueEdit(listOf(ReminderAlert(ReminderAlertType.AT_TIME)), null, null))
}
