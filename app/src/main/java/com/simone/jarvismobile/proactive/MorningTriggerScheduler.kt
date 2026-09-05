package com.simone.jarvismobile.proactive

import android.app.AlarmManager
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.simone.jarvismobile.alarms.ExactAlarms
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § FASE 2A.8 RELEASE GATE F — MULTI-SIGNAL MORNING COORDINATOR. Root cause
 * audited: the morning briefing used to depend entirely on the real
 * `ACTION_USER_PRESENT` unlock event (only delivered when "Automazioni in
 * background" is on) plus a coarse periodic fallback (up to 1h late, and
 * silenced when the automation service is on — see [ProactiveManager]'s own
 * doc comments for that history). This schedules TWO independent, higher-
 * quality signals as real exact alarms — reusing [ExactAlarms]/[com.simone.jarvismobile.alarms.AlarmReceiver],
 * the same channel reminders/rules already use, never a second scheduler:
 *
 *  - **NEXT_ALARM**: [AlarmManager.getNextAlarmClock] (already read once by
 *    [ProactiveManager.nextAlarmTime] for the briefing's own content) plus a
 *    configurable offset (default +5min, [SettingsRepository.morningNextAlarmOffsetMinutes]) —
 *    the user is realistically awake shortly after their alarm rings, not at
 *    the instant of the alarm-manager wakeup.
 *  - **CONFIGURED_TIME**: [SettingsRepository.morningBriefingHour]/[SettingsRepository.morningBriefingMinute],
 *    a MANDATORY daily fallback — always scheduled, so the briefing never
 *    depends solely on an alarm existing or a real unlock happening.
 *
 * FIRST_UNLOCK ([com.simone.jarvismobile.automation.AutomationEventService])
 * remains as an additional, already-existing safety signal — untouched here.
 * All three converge on the exact same [ProactiveManager.evaluateOnUnlock]
 * call and the exact same governor per-day dedup key, so no combination of
 * simultaneous triggers can ever double-deliver.
 *
 * **HUAWEI_SLEEP deliberately NOT implemented**: it would need a
 * `NotificationListenerService` reading Huawei Health's own sleep-report
 * notification with explicit user consent and a prudent, low-confidence-safe
 * text classification — real content-sniffing of another app's notifications
 * this project has never done before, and getting the classification wrong
 * risks either missing real sleep data or (worse) misreading an unrelated
 * notification as one. Per the spec's own instruction ("ignore if not
 * confident"), this is left out rather than guessed at; NEXT_ALARM and
 * CONFIGURED_TIME already close the "no digest depends solely on unlock"
 * requirement on their own.
 */
@Singleton
class MorningTriggerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exactAlarms: ExactAlarms,
    private val settings: SettingsRepository,
) {
    /** Re-arms both signals — called at app start/boot, and after either fires (self-healing: a missed `ACTION_NEXT_ALARM_CLOCK_CHANGED` broadcast never leaves NEXT_ALARM stale for more than one cycle). */
    suspend fun scheduleAll() {
        scheduleNextAlarmTrigger()
        scheduleConfiguredTimeTrigger()
    }

    /** Re-reads the device's next alarm and (re)schedules the NEXT_ALARM firing, or cancels it if no alarm is set. */
    suspend fun scheduleNextAlarmTrigger() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val triggerAtMs = runCatching { am?.nextAlarmClock?.triggerTime }.getOrNull()
        if (triggerAtMs == null) {
            exactAlarms.cancel(KEY_NEXT_ALARM)
            return
        }
        val offsetMinutes = settings.morningNextAlarmOffsetMinutes.first()
        val fireAt = Instant.ofEpochMilli(triggerAtMs)
            .plusSeconds(offsetMinutes * 60L)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        // An alarm whose offset window has already passed (e.g. rescheduled
        // while this ran) is never fired immediately as a side effect —
        // CONFIGURED_TIME/FIRST_UNLOCK remain the signals for today.
        if (!fireAt.isAfter(LocalDateTime.now())) {
            exactAlarms.cancel(KEY_NEXT_ALARM)
            return
        }
        exactAlarms.schedule(
            key = KEY_NEXT_ALARM,
            at = fireAt,
            extras = mapOf(
                ExactAlarms.EXTRA_KIND to ExactAlarms.KIND_MORNING_BRIEFING,
                ExactAlarms.EXTRA_ID to KEY_NEXT_ALARM,
                ExactAlarms.EXTRA_TRIGGER_SOURCE to "NEXT_ALARM",
            ),
        )
    }

    /** Always scheduled — the mandatory fallback, independent of whether any device alarm exists. */
    suspend fun scheduleConfiguredTimeTrigger() {
        val hour = settings.morningBriefingHour.first()
        val minute = settings.morningBriefingMinute.first()
        val now = LocalDateTime.now()
        var fireAt = now.toLocalDate().atTime(hour, minute)
        if (!fireAt.isAfter(now)) fireAt = fireAt.plusDays(1)
        exactAlarms.schedule(
            key = KEY_CONFIGURED_TIME,
            at = fireAt,
            extras = mapOf(
                ExactAlarms.EXTRA_KIND to ExactAlarms.KIND_MORNING_BRIEFING,
                ExactAlarms.EXTRA_ID to KEY_CONFIGURED_TIME,
                ExactAlarms.EXTRA_TRIGGER_SOURCE to "CONFIGURED_TIME",
            ),
        )
    }

    /**
     * § FASE 2A.8 RELEASE GATE G — POST-BRIEFING MORNING REFRESH. Called only
     * by [ProactiveManager] right after it REALLY delivered a MORNING_DIGEST
     * (never on a Skip, never for EVENING_DIGEST). Enqueues two unique
     * one-time [MorningRefreshWorker] runs (+10min main attempt, catching a
     * Huawei Health→Health Connect sync that landed just after the digest
     * itself; +60min safety retry) — `ExistingWorkPolicy.KEEP` so a duplicate
     * call the same morning (e.g. a race between two trigger sources, already
     * prevented one layer up by the governor's dedup, but cheap insurance
     * here too) never double-books. WorkManager, not [ExactAlarms]: unlike the
     * briefing itself, a refresh a few minutes late to Doze is an acceptable
     * degradation, not a broken promise.
     */
    fun schedulePostBriefingRefreshes() {
        val workManager = WorkManager.getInstance(context)
        val requestedAtMs = System.currentTimeMillis()
        val today = LocalDate.now()
        listOf(10L to "+10min", 60L to "+60min").forEach { (delayMinutes, label) ->
            val data = Data.Builder()
                .putString(MorningRefreshWorker.KEY_DELAY_LABEL, label)
                .putLong(MorningRefreshWorker.KEY_REQUESTED_AT_MS, requestedAtMs)
                .build()
            val request = OneTimeWorkRequestBuilder<MorningRefreshWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(data)
                .addTag(TAG_POST_BRIEFING_REFRESH)
                .build()
            workManager.enqueueUniqueWork("${WORK_NAME_PREFIX}${label}_$today", ExistingWorkPolicy.KEEP, request)
        }
    }

    /** One entry per attempted post-briefing refresh (§F/§G diagnostics) — never the notification text itself, only timing/outcome. */
    data class PostBriefingRefreshDiagnostic(
        val delayLabel: String,
        val requestedAtMs: Long,
        val actualRunAtMs: Long,
        val outcome: String,
    )

    private val _lastPostBriefingRefreshes = MutableStateFlow<List<PostBriefingRefreshDiagnostic>>(emptyList())
    val lastPostBriefingRefreshes: StateFlow<List<PostBriefingRefreshDiagnostic>> = _lastPostBriefingRefreshes.asStateFlow()

    /** Called by [MorningRefreshWorker] itself once it has actually run. */
    fun recordPostBriefingRefresh(delayLabel: String, requestedAtMs: Long, actualRunAtMs: Long, outcome: String) {
        _lastPostBriefingRefreshes.value =
            (_lastPostBriefingRefreshes.value + PostBriefingRefreshDiagnostic(delayLabel, requestedAtMs, actualRunAtMs, outcome))
                .takeLast(10)
    }

    companion object {
        const val KEY_NEXT_ALARM = "morning_next_alarm"
        const val KEY_CONFIGURED_TIME = "morning_configured_time"
        private const val WORK_NAME_PREFIX = "jarvis_morning_refresh_"
        private const val TAG_POST_BRIEFING_REFRESH = "jarvis_morning_refresh"
    }
}
