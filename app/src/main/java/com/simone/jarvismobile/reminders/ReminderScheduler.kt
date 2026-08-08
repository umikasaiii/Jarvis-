package com.simone.jarvismobile.reminders

import android.content.Context
import com.simone.jarvismobile.alarms.ExactAlarms
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.ReminderSchedule
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Reconciles Agenda.md alert rules with WorkManager's persistent schedule. */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val alarms: ExactAlarms,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun sync(entries: List<AgendaEntry>) {
        if (!settings.reminderNotifications.first()) {
            cancelAllKnown()
            return
        }
        val morning = LocalTime.of(settings.reminderMorningHour.first(), 0)
        val now = LocalDateTime.now()
        val desired = LinkedHashMap<String, ScheduledReminder>()

        entries.filterNot { it.done }.forEach { entry ->
            entry.alerts.distinctBy { it.key }.forEach alerts@{ alert ->
                val trigger = ReminderSchedule.triggerAt(entry, alert, morning) ?: return@alerts
                if (!trigger.isAfter(now)) return@alerts
                val name = workName(entry.id, alert.key)
                desired[name] = ScheduledReminder(entry, alert.key, trigger)
            }
        }

        val old = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        (old - desired.keys).forEach(alarms::cancel)
        desired.forEach { (name, reminder) -> enqueue(name, reminder) }
        prefs.edit().putStringSet(KEY_SCHEDULED, desired.keys.toSet()).apply()
    }

    suspend fun cancelEntry(entryId: String) {
        val old = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        val prefix = "jarvis_reminder_${entryId}_"
        val removed = old.filter { it.startsWith(prefix) }.toSet()
        removed.forEach(alarms::cancel)
        prefs.edit().putStringSet(KEY_SCHEDULED, old - removed).apply()
    }

    /**
     * An exact alarm, not deferrable work. A reminder is a promise about a
     * minute; WorkManager only ever promised "eventually", and on a sleeping
     * phone that meant the notification turned up when the app was next opened.
     */
    private fun enqueue(name: String, reminder: ScheduledReminder) {
        val entry = reminder.entry
        val whenText = listOfNotNull(
            entry.date?.toString(),
            entry.time?.toString(),
        ).joinToString(" · ")
        alarms.schedule(
            key = name,
            at = reminder.trigger,
            extras = mapOf(
                ExactAlarms.EXTRA_KIND to ExactAlarms.KIND_REMINDER,
                ExactAlarms.EXTRA_ID to "${entry.id}_${reminder.alertKey}",
                ExactAlarms.EXTRA_TITLE to entry.text,
                ExactAlarms.EXTRA_SUBTEXT to whenText,
            ),
        )
    }

    private fun cancelAllKnown() {
        val old = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        old.forEach(alarms::cancel)
        prefs.edit().remove(KEY_SCHEDULED).apply()
    }

    private fun workName(entryId: String, alertKey: String) =
        "jarvis_reminder_${entryId}_${alertKey.hashCode().toUInt().toString(16)}"

    private data class ScheduledReminder(
        val entry: AgendaEntry,
        val alertKey: String,
        val trigger: LocalDateTime,
    )

    companion object {
        const val TAG_REMINDERS = "jarvis_reminders"
        private const val PREFS = "jarvis_reminder_schedule"
        private const val KEY_SCHEDULED = "scheduled_work"
    }
}
