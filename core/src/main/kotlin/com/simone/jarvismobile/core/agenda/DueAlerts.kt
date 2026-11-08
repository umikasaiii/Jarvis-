package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import java.time.LocalTime

/**
 * Keeps the notification rules of a task coherent when its due date/time is
 * edited in the task editor (BETA 3). Creating a task already gave a dated
 * task a default alert; *editing* a due date onto a task that had none did
 * not, so a deadline added later never produced a notification.
 */
object DueAlerts {
    /**
     * - Becoming dated with no alert rules → one default rule (at time if timed, otherwise the morning of).
     * - Already has rules → the user's choice is kept untouched.
     * - Losing its date → rules are dropped (there is nothing to fire on).
     */
    fun afterDueEdit(existing: List<ReminderAlert>, newDate: LocalDate?, newTime: LocalTime?): List<ReminderAlert> = when {
        newDate == null -> emptyList()
        existing.isNotEmpty() -> existing
        else -> listOf(ReminderAlert(if (newTime != null) ReminderAlertType.AT_TIME else ReminderAlertType.MORNING_OF))
    }
}
