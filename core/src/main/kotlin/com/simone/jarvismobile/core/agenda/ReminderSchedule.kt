package com.simone.jarvismobile.core.agenda

import java.time.LocalDateTime
import java.time.LocalTime

/** Pure reminder-time arithmetic, separate from Android scheduling. */
object ReminderSchedule {
    fun triggerAt(
        entry: AgendaEntry,
        alert: ReminderAlert,
        morningTime: LocalTime = LocalTime.of(8, 0),
    ): LocalDateTime? {
        val eventAt = entry.date.atTime(entry.time ?: morningTime)
        return when (alert.type) {
            ReminderAlertType.AT_TIME -> entry.time?.let { entry.date.atTime(it) }
            ReminderAlertType.MORNING_OF -> entry.date.atTime(morningTime)
            ReminderAlertType.ONE_DAY_BEFORE -> eventAt.minusDays(1)
            ReminderAlertType.TWO_DAYS_BEFORE -> eventAt.minusDays(2)
            ReminderAlertType.THREE_DAYS_BEFORE -> eventAt.minusDays(3)
            ReminderAlertType.ONE_WEEK_BEFORE -> eventAt.minusWeeks(1)
            ReminderAlertType.CUSTOM -> alert.customAt
        }
    }
}
