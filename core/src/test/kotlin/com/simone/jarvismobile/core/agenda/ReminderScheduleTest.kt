package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReminderScheduleTest {
    private val entry = AgendaEntry(
        date = LocalDate.of(2026, 8, 10),
        time = LocalTime.of(15, 30),
        text = "dentista",
    )

    @Test
    fun `morning and day offsets are deterministic`() {
        assertEquals(
            LocalDateTime.of(2026, 8, 10, 8, 0),
            ReminderSchedule.triggerAt(entry, ReminderAlert(ReminderAlertType.MORNING_OF)),
        )
        assertEquals(
            LocalDateTime.of(2026, 8, 8, 15, 30),
            ReminderSchedule.triggerAt(entry, ReminderAlert(ReminderAlertType.TWO_DAYS_BEFORE)),
        )
    }

    @Test
    fun `at time is unavailable for an untimed task`() {
        assertNull(
            ReminderSchedule.triggerAt(
                entry.copy(time = null),
                ReminderAlert(ReminderAlertType.AT_TIME),
            ),
        )
    }
}
