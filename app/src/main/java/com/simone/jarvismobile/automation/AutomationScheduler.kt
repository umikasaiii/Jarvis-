package com.simone.jarvismobile.automation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.core.automation.Trigger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides when a time-based automation should next run, and asks the platform to
 * wake us then.
 *
 * The "when" is computed here and unit-testable in principle; the "ask the
 * platform" is one method, [enqueueAt], and that is deliberate. WorkManager is
 * a deferrable scheduler — the system decides when, and on aggressive OEM
 * builds a frozen app is simply never run, which is why reminders currently
 * arrive in a batch the moment the app is opened. Moving to exact alarms is the
 * next piece of work and it needs to change this one method, not every caller.
 *
 * Condition triggers (battery, charger) are not scheduled at all: they are
 * evaluated when the system broadcasts the event, in [PowerEventReceiver].
 */
@Singleton
class AutomationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Re-points the schedule at exactly the enabled time rules, nothing else. */
    fun sync(automations: List<Automation>, now: LocalDateTime = LocalDateTime.now()) {
        val desired = LinkedHashMap<String, LocalDateTime>()
        automations.filter { it.enabled }.forEach { rule ->
            val trigger = rule.trigger as? Trigger.TimeOfDay ?: return@forEach
            desired[workName(rule.id)] = nextRun(trigger, now)
        }

        val known = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        (known - desired.keys).forEach(workManager::cancelUniqueWork)
        desired.forEach { (name, at) -> enqueueAt(name, name.removePrefix(PREFIX), at) }
        prefs.edit().putStringSet(KEY_SCHEDULED, desired.keys.toSet()).apply()
    }

    fun cancel(id: String) {
        val name = workName(id)
        workManager.cancelUniqueWork(name)
        val known = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        prefs.edit().putStringSet(KEY_SCHEDULED, known - name).apply()
    }

    /**
     * The next moment this rule is due: today if the time has not passed and the
     * weekday is selected, otherwise the first selected day after that. An empty
     * day set means every day.
     */
    fun nextRun(trigger: Trigger.TimeOfDay, now: LocalDateTime): LocalDateTime {
        var candidate = now.toLocalDate().atTime(trigger.time)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        // A week is enough: a non-empty day set always contains one of the seven.
        repeat(8) {
            if (trigger.matches(candidate.dayOfWeek)) return candidate
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    /**
     * The single point where a time becomes a platform wake-up. Swap this for an
     * exact alarm and every automation becomes punctual at once.
     */
    private fun enqueueAt(workName: String, automationId: String, at: LocalDateTime) {
        val delay = Duration.between(Instant.now(), at.atZone(ZoneId.systemDefault()).toInstant())
            .toMillis()
            .coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AutomationWorker.KEY_ID to automationId))
            .addTag(TAG_AUTOMATIONS)
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }

    private fun workName(id: String) = "$PREFIX$id"

    companion object {
        const val TAG_AUTOMATIONS = "jarvis_automations"
        private const val PREFIX = "jarvis_automation_"
        private const val PREFS = "jarvis_automation_schedule"
        private const val KEY_SCHEDULED = "scheduled_work"
    }
}
