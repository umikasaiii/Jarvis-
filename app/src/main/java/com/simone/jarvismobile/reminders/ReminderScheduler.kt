package com.simone.jarvismobile.reminders

import android.content.Context
import com.simone.jarvismobile.alarms.ExactAlarms
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.ReminderSchedule
import com.simone.jarvismobile.core.reliability.MissedReminderPolicy
import com.simone.jarvismobile.core.reliability.ReminderCandidate
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
    private val ledger = ReminderDeliveryLedger(context)

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
        // BETA 7: the result of booking used to be ignored, so an alarm the platform refused was still remembered as
        // scheduled. Only accepted bookings are recorded; refused ones are retried on the next sync/re-arm.
        val accepted = desired.filter { (name, reminder) -> enqueue(name, reminder) }.keys
        prefs.edit().putStringSet(KEY_SCHEDULED, accepted.toSet()).apply()
    }

    /**
     * Reminders whose moment passed while the phone was off or the app was force-stopped, not yet delivered, within
     * the last hours: posted once, marked as missed. Called only from a restart/re-arm pass — never on an ordinary
     * edit, so moving a reminder into the past does not make it fire.
     * @return how many were posted
     */
    suspend fun deliverMissed(entries: List<AgendaEntry>, now: LocalDateTime = LocalDateTime.now()): Int {
        if (!settings.reminderNotifications.first()) return 0
        val morning = LocalTime.of(settings.reminderMorningHour.first(), 0)
        val byKey = LinkedHashMap<String, Pair<AgendaEntry, LocalDateTime>>()
        entries.filterNot { it.done }.forEach { entry ->
            entry.alerts.distinctBy { it.key }.forEach alerts@{ alert ->
                val trigger = ReminderSchedule.triggerAt(entry, alert, morning) ?: return@alerts
                byKey["${entry.id}_${alert.key}"] = entry to trigger
            }
        }
        ledger.retainOnly(byKey.keys)
        val missed = MissedReminderPolicy.select(
            byKey.map { (k, v) -> ReminderCandidate(k, v.second) },
            ledger.delivered(),
            now,
        )
        var posted = 0
        missed.forEach { candidate ->
            val (entry, _) = byKey.getValue(candidate.key)
            val whenText = listOfNotNull(entry.date?.toString(), entry.time?.toString()).joinToString(" · ")
            val outcome = ReminderNotifier.post(context, candidate.key, entry.id, entry.text, whenText, missed = true)
            ledger.record(candidate.key, outcome)
            if (outcome == ReminderPostOutcome.POSTED) posted++
        }
        return posted
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
    private fun enqueue(name: String, reminder: ScheduledReminder): Boolean {
        val entry = reminder.entry
        val whenText = listOfNotNull(
            entry.date?.toString(),
            entry.time?.toString(),
        ).joinToString(" · ")
        return alarms.schedule(
            key = name,
            at = reminder.trigger,
            extras = mapOf(
                ExactAlarms.EXTRA_KIND to ExactAlarms.KIND_REMINDER,
                ExactAlarms.EXTRA_ID to "${entry.id}_${reminder.alertKey}",
                ExactAlarms.EXTRA_ENTRY_ID to entry.id,
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
