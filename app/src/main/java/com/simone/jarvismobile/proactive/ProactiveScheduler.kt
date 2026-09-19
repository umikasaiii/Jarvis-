package com.simone.jarvismobile.proactive

import android.app.AlarmManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.simone.jarvismobile.alarms.ExactAlarms
import com.simone.jarvismobile.core.proactive.ProactiveSchedulePlan
import com.simone.jarvismobile.core.proactive.ProactiveTriggerSource
import com.simone.jarvismobile.core.proactive.ScheduleExactness
import com.simone.jarvismobile.core.proactive.SourceAlarmReconciler
import com.simone.jarvismobile.core.proactive.TriggerEvidenceSource
import com.simone.jarvismobile.core.proactive.TriggerEvidenceStage
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §3 (SINGLE TEMPORAL OWNER). The canonical owner of
 * proactivity temporal planning/reconciliation: FIRST_UNLOCK readiness is
 * observed elsewhere ([com.simone.jarvismobile.automation.AutomationEventService],
 * §11, untouched here) but NEXT_ALARM and CONFIGURED_TIME — the two signals
 * [MorningTriggerScheduler] used to own — are now planned and reconciled
 * HERE, and only here. [MorningTriggerScheduler]'s scheduling authority is
 * REMOVED (§3 option A): it is reduced to the unrelated post-briefing data
 * refresh scheduler it also happened to carry (its own doc comment says so).
 * Periodic due recovery ([sync]/[apply], pre-existing, unchanged) stays
 * here too — there is now exactly one class a future reader needs to open
 * to understand "when does a proactivity trigger fire".
 *
 * A trigger/scheduler is NEVER allowed to bypass the dispatcher (§2): this
 * class only ever produces a typed [ProactiveTriggerSource] firing through
 * [com.simone.jarvismobile.alarms.AlarmReceiver] into
 * [ProactiveManager.evaluateOnUnlock] — the same single occurrence-claiming
 * entry point Work Package A already made the sole dispatch authority.
 */
@Singleton
class ProactiveScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val exactAlarms: ExactAlarms,
    private val planStore: ProactiveSchedulePlanStore,
    private val evidence: TriggerEvidenceStore,
) {
    private val workManager = WorkManager.getInstance(context)

    // --- periodic due-recovery (pre-existing, unchanged) --------------------

    suspend fun sync() {
        if (!settings.proactiveEnabled.first()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        apply(true)
    }

    fun apply(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(1, TimeUnit.HOURS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    // --- canonical NEXT_ALARM / CONFIGURED_TIME temporal ownership ---------

    /** Re-arms both exact-alarm signals — called at app start/boot and after either fires. */
    suspend fun scheduleAll() {
        reconcileNextAlarm()
        reconcileConfiguredTime()
    }

    /**
     * § §8/§9. Reconciles the NEXT_ALARM plan against a fresh
     * `AlarmManager.getNextAlarmClock()` observation. This is the ONLY place
     * NEXT_ALARM is ever (re)computed — called on service start, on every
     * runtime `ACTION_NEXT_ALARM_CLOCK_CHANGED` observation
     * ([com.simone.jarvismobile.automation.AutomationEventService], §8), and
     * from [scheduleAll] at boot/app-start. [SourceAlarmReconciler] is the
     * pure decision this wraps: a post-maturity `null`/changed reading can
     * never cancel an already-valid, not-yet-fired occurrence (§9).
     */
    suspend fun reconcileNextAlarm() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val nowMs = System.currentTimeMillis()
        val today = LocalDate.now().toString()

        val newlyObserved = runCatching { am?.nextAlarmClock?.triggerTime }
            .onFailure { evidence.record(TriggerEvidenceSource.NEXT_ALARM, TriggerEvidenceStage.NEXT_ALARM_READ_FAILED, detail = "error=${it.javaClass.simpleName}") }
            .getOrNull()
        evidence.record(TriggerEvidenceSource.NEXT_ALARM, TriggerEvidenceStage.NEXT_ALARM_READ, detail = "observed=$newlyObserved")

        val existing = planStore.currentPlan(ProactiveTriggerSource.NEXT_ALARM)
        // A plan from a previous logical date is never "previous" for today's
        // reconciliation — each day starts fresh (§9's own "all remain
        // subordinate to MORNING_DIGEST:<logicalDate>").
        val relevantExisting = existing?.takeIf { it.logicalDate == today }
        val offsetMinutes = settings.morningNextAlarmOffsetMinutes.first()

        val decision = SourceAlarmReconciler.reconcile(
            previousSourceAlarmAtMs = relevantExisting?.sourceAlarmAtMs,
            previousFireAtMs = relevantExisting?.intendedFireAtMs,
            newlyObservedSourceAlarmAtMs = newlyObserved,
            nowMs = nowMs,
            offsetMs = offsetMinutes * 60_000L,
        )

        when (decision) {
            is SourceAlarmReconciler.Decision.NoSource -> {
                exactAlarms.cancel(KEY_NEXT_ALARM)
                evidence.record(TriggerEvidenceSource.NEXT_ALARM, TriggerEvidenceStage.NEXT_ALARM_ABSENT)
                planStore.upsert(
                    planEntity(
                        source = ProactiveTriggerSource.NEXT_ALARM, revision = nextRevision(existing), logicalDate = today,
                        fireAtMs = 0L, sourceAlarmAtMs = null, enabled = false, outcome = "NO_SOURCE",
                        exactness = ScheduleExactness.NOT_SCHEDULED, nowMs = nowMs,
                    ),
                )
            }
            is SourceAlarmReconciler.Decision.NoOp, is SourceAlarmReconciler.Decision.KeepExisting -> {
                // Nothing to schedule or cancel — including the critical §9
                // freeze: a matured, already-valid occurrence is untouched.
            }
            is SourceAlarmReconciler.Decision.Schedule -> {
                val revision = nextRevision(existing)
                val fireAtLdt = Instant.ofEpochMilli(decision.fireAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
                evidence.record(TriggerEvidenceSource.NEXT_ALARM, TriggerEvidenceStage.NEXT_ALARM_SCHEDULE_ATTEMPTED, detail = "fireAt=$fireAtLdt offsetMinutes=$offsetMinutes")
                val outcome = exactAlarms.scheduleWithOutcome(
                    key = KEY_NEXT_ALARM,
                    at = fireAtLdt,
                    extras = mapOf(
                        ExactAlarms.EXTRA_KIND to ExactAlarms.KIND_MORNING_BRIEFING,
                        ExactAlarms.EXTRA_ID to KEY_NEXT_ALARM,
                        ExactAlarms.EXTRA_TRIGGER_SOURCE to ProactiveTriggerSource.NEXT_ALARM.name,
                        ExactAlarms.EXTRA_PLAN_REVISION to revision.toString(),
                        ExactAlarms.EXTRA_LOGICAL_DATE to today,
                    ),
                )
                recordScheduleOutcome(TriggerEvidenceSource.NEXT_ALARM, outcome, TriggerEvidenceStage.NEXT_ALARM_SCHEDULED, TriggerEvidenceStage.NEXT_ALARM_SCHEDULE_FAILED, "fireAt=$fireAtLdt")
                planStore.upsert(
                    planEntity(
                        source = ProactiveTriggerSource.NEXT_ALARM, revision = revision, logicalDate = today,
                        fireAtMs = decision.fireAtMs, sourceAlarmAtMs = decision.sourceAlarmAtMs, enabled = true,
                        outcome = outcome.name, exactness = exactnessFor(outcome), nowMs = nowMs,
                    ),
                )
            }
        }
    }

    /**
     * § §5/§7. Always scheduled — the mandatory fallback, independent of any
     * device alarm. Reads hour/minute/offset/enabled as ONE coherent
     * snapshot ([SettingsRepository.morningScheduleSettings]) rather than
     * two split flow reads that could straddle a concurrent edit. This is
     * the ONLY place CONFIGURED_TIME is ever (re)computed; every reschedule
     * bumps the plan revision, so a stale intent from before the edit is
     * rejected by [com.simone.jarvismobile.alarms.AlarmReceiver] at fire
     * time rather than silently recomputed against the new settings.
     */
    suspend fun reconcileConfiguredTime() {
        val snapshot = settings.morningScheduleSettings.first()
        val now = LocalDateTime.now()
        var fireAt = now.toLocalDate().atTime(snapshot.hour, snapshot.minute)
        if (!fireAt.isAfter(now)) fireAt = fireAt.plusDays(1)
        val today = now.toLocalDate().toString()
        val fireAtMs = fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val existing = planStore.currentPlan(ProactiveTriggerSource.CONFIGURED_TIME)
        val coherentSnapshotChanged = existing == null ||
            existing.logicalDate != today ||
            existing.intendedFireAtMs != fireAtMs ||
            existing.settingsSnapshotHour != snapshot.hour ||
            existing.settingsSnapshotMinute != snapshot.minute

        if (!coherentSnapshotChanged) {
            // Already reconciled to this exact coherent snapshot — no need
            // to rebook the same exact alarm under a new, needlessly higher
            // revision.
            return
        }

        val revision = nextRevision(existing)
        evidence.record(TriggerEvidenceSource.CONFIGURED_TIME, TriggerEvidenceStage.CONFIGURED_TIME_RECONCILED, detail = "hour=${snapshot.hour} minute=${snapshot.minute} revision=$revision")
        evidence.record(TriggerEvidenceSource.CONFIGURED_TIME, TriggerEvidenceStage.CONFIGURED_TIME_SCHEDULE_ATTEMPTED, detail = "fireAt=$fireAt")
        val outcome = exactAlarms.scheduleWithOutcome(
            key = KEY_CONFIGURED_TIME,
            at = fireAt,
            extras = mapOf(
                ExactAlarms.EXTRA_KIND to ExactAlarms.KIND_MORNING_BRIEFING,
                ExactAlarms.EXTRA_ID to KEY_CONFIGURED_TIME,
                ExactAlarms.EXTRA_TRIGGER_SOURCE to ProactiveTriggerSource.CONFIGURED_TIME.name,
                ExactAlarms.EXTRA_PLAN_REVISION to revision.toString(),
                ExactAlarms.EXTRA_LOGICAL_DATE to today,
            ),
        )
        recordScheduleOutcome(TriggerEvidenceSource.CONFIGURED_TIME, outcome, TriggerEvidenceStage.CONFIGURED_TIME_SCHEDULED, TriggerEvidenceStage.CONFIGURED_TIME_SCHEDULE_FAILED, "fireAt=$fireAt")
        planStore.upsert(
            ProactiveSchedulePlanEntity(
                source = ProactiveTriggerSource.CONFIGURED_TIME.name,
                planRevision = revision,
                logicalDate = today,
                intendedFireAtMs = fireAtMs,
                sourceAlarmAtMs = null,
                settingsSnapshotHour = snapshot.hour,
                settingsSnapshotMinute = snapshot.minute,
                settingsSnapshotOffsetMinutes = snapshot.nextAlarmOffsetMinutes,
                enabled = true,
                reconciliationOutcome = outcome.name,
                exactness = exactnessFor(outcome).name,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** § §15 — the plan a fired intent must validate its revision/date against. Read-only, never mutates. */
    suspend fun currentPlan(source: ProactiveTriggerSource): ProactiveSchedulePlan? =
        planStore.currentPlan(source)?.let {
            ProactiveSchedulePlan(
                source = source,
                planRevision = it.planRevision,
                logicalDate = it.logicalDate,
                intendedFireAtMs = it.intendedFireAtMs,
                sourceAlarmAtMs = it.sourceAlarmAtMs,
                enabled = it.enabled,
                reconciliationOutcome = it.reconciliationOutcome,
                exactness = runCatching { ScheduleExactness.valueOf(it.exactness) }.getOrDefault(ScheduleExactness.NOT_SCHEDULED),
                updatedAtMs = it.updatedAtMs,
            )
        }

    private fun nextRevision(existing: ProactiveSchedulePlanEntity?): Long = (existing?.planRevision ?: 0L) + 1

    private fun planEntity(
        source: ProactiveTriggerSource,
        revision: Long,
        logicalDate: String,
        fireAtMs: Long,
        sourceAlarmAtMs: Long?,
        enabled: Boolean,
        outcome: String,
        exactness: ScheduleExactness,
        nowMs: Long,
    ) = ProactiveSchedulePlanEntity(
        source = source.name,
        planRevision = revision,
        logicalDate = logicalDate,
        intendedFireAtMs = fireAtMs,
        sourceAlarmAtMs = sourceAlarmAtMs,
        settingsSnapshotHour = null,
        settingsSnapshotMinute = null,
        settingsSnapshotOffsetMinutes = null,
        enabled = enabled,
        reconciliationOutcome = outcome,
        exactness = exactness.name,
        updatedAtMs = nowMs,
    )

    private fun exactnessFor(outcome: ExactAlarms.ScheduleOutcome): ScheduleExactness = when (outcome) {
        ExactAlarms.ScheduleOutcome.SCHEDULED_EXACT -> ScheduleExactness.EXACT
        ExactAlarms.ScheduleOutcome.SCHEDULED_INEXACT_PERMISSION_MISSING -> ScheduleExactness.DEGRADED_INEXACT
        ExactAlarms.ScheduleOutcome.SECURITY_EXCEPTION, ExactAlarms.ScheduleOutcome.FAILED -> ScheduleExactness.NOT_SCHEDULED
    }

    /** § §14 — never a generic pass/fail: the exact-alarm-permission state is recorded as its own distinct checkpoint too. */
    private suspend fun recordScheduleOutcome(
        source: TriggerEvidenceSource,
        outcome: ExactAlarms.ScheduleOutcome,
        scheduledStage: TriggerEvidenceStage,
        failedStage: TriggerEvidenceStage,
        detail: String,
    ) {
        when (outcome) {
            ExactAlarms.ScheduleOutcome.SCHEDULED_EXACT -> evidence.record(source, scheduledStage, detail = "$detail exact=true")
            ExactAlarms.ScheduleOutcome.SCHEDULED_INEXACT_PERMISSION_MISSING -> {
                evidence.record(source, scheduledStage, detail = "$detail exact=false")
                evidence.record(source, TriggerEvidenceStage.EXACT_ALARM_PERMISSION_MISSING)
            }
            ExactAlarms.ScheduleOutcome.SECURITY_EXCEPTION -> {
                evidence.record(source, failedStage, detail = "reason=security_exception")
                evidence.record(source, TriggerEvidenceStage.EXACT_ALARM_SECURITY_EXCEPTION)
            }
            ExactAlarms.ScheduleOutcome.FAILED -> evidence.record(source, failedStage, detail = "reason=pending_intent_or_unknown")
        }
    }

    companion object {
        const val KEY_NEXT_ALARM = "morning_next_alarm"
        const val KEY_CONFIGURED_TIME = "morning_configured_time"
        private const val WORK_NAME = "jarvis_proactive_check"
        private const val TAG = "jarvis_proactive"
    }
}
