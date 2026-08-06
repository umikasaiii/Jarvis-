package com.simone.jarvismobile.reminders

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
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
) {
    private val workManager = WorkManager.getInstance(context)
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
        (old - desired.keys).forEach(workManager::cancelUniqueWork)
        desired.forEach { (name, reminder) -> enqueue(name, reminder) }
        prefs.edit().putStringSet(KEY_SCHEDULED, desired.keys.toSet()).apply()
    }

    suspend fun cancelEntry(entryId: String) {
        val old = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        val prefix = "jarvis_reminder_${entryId}_"
        val removed = old.filter { it.startsWith(prefix) }.toSet()
        removed.forEach(workManager::cancelUniqueWork)
        prefs.edit().putStringSet(KEY_SCHEDULED, old - removed).apply()
    }

    private fun enqueue(name: String, reminder: ScheduledReminder) {
        val zone = ZoneId.systemDefault()
        val delay = Duration.between(java.time.Instant.now(), reminder.trigger.atZone(zone).toInstant())
            .toMillis().coerceAtLeast(0)
        val entry = reminder.entry
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ReminderWorker.KEY_ENTRY_ID to entry.id,
                    ReminderWorker.KEY_TITLE to entry.text,
                    ReminderWorker.KEY_DATE to entry.date.toString(),
                    ReminderWorker.KEY_TIME to (entry.time?.toString() ?: ""),
                    ReminderWorker.KEY_ALERT_KEY to reminder.alertKey,
                ),
            )
            .addTag(TAG_REMINDERS)
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancelAllKnown() {
        val old = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        old.forEach(workManager::cancelUniqueWork)
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
