package com.simone.jarvismobile.alarms

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.automation.AutomationRepository
import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.automation.rule.RuleScheduler
import com.simone.jarvismobile.core.proactive.TriggerEvidenceSource
import com.simone.jarvismobile.core.proactive.TriggerEvidenceStage
import com.simone.jarvismobile.core.reliability.RearmReport
import com.simone.jarvismobile.core.reliability.RearmRunner
import com.simone.jarvismobile.core.reliability.RearmStep
import com.simone.jarvismobile.core.reliability.RearmTrigger
import com.simone.jarvismobile.proactive.ProactiveScheduler
import com.simone.jarvismobile.proactive.TriggerEvidenceStore
import com.simone.jarvismobile.reminders.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BETA 7 — the ONE re-arm entry point. Used by [BootReceiver] (reboot / app update), [TimeChangeReceiver] (zone, clock,
 * exact-alarm permission) and [RearmWorker] (persistent safety net). It owns no scheduling decision of its own: each
 * step just asks the existing owner (agenda / automations / rule engine / places / [ProactiveScheduler]) to rebuild its
 * schedule from its own source of truth, so there is still exactly one temporal owner per feature.
 *
 * Every step is isolated and bounded by [RearmRunner]; the outcome is persisted (step names and statuses only, no
 * user content) so "were my alarms restored after the reboot?" has a checkable answer.
 */
@Singleton
class AlarmRearmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agenda: AgendaRepository,
    private val automations: AutomationRepository,
    private val rules: RuleScheduler,
    private val places: PlaceRepository,
    private val proactive: ProactiveScheduler,
    private val reminders: ReminderScheduler,
    private val backups: com.simone.jarvismobile.backup.BackupScheduler,
    private val evidence: TriggerEvidenceStore,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun rearm(trigger: RearmTrigger): RearmReport {
        val catchUpMissed = trigger == RearmTrigger.BOOT ||
            trigger == RearmTrigger.PACKAGE_REPLACED ||
            trigger == RearmTrigger.WORKER_SAFETY_NET
        val actions: Map<RearmStep, suspend () -> Unit> = mapOf(
            RearmStep.AGENDA_REMINDERS to {
                val entries = agenda.reload()
                // reload() re-books every future reminder; reminders missed during the outage are posted once.
                if (catchUpMissed) reminders.deliverMissed(entries)
                Unit
            },
            RearmStep.AUTOMATIONS to { automations.reload(); Unit },
            RearmStep.RULES to { rules.sync() },
            RearmStep.PLACES to { places.reload() },
            RearmStep.PROACTIVE to { proactive.scheduleAll(rebook = true) },
            // WorkManager keeps the periodic backup across reboots, but its initial delay was computed for the old zone/clock.
            RearmStep.BACKUP_SCHEDULE to { backups.sync() },
        )
        val report = RearmRunner().run(trigger, actions)
        persist(report)
        Log.i(TAG, "rearm ${report.summary()}")
        runCatching {
            if (report.allOk) {
                evidence.record(TriggerEvidenceSource.COMMON, TriggerEvidenceStage.REARM_COMPLETED, detail = "trigger=${trigger.name}")
            } else {
                evidence.record(
                    TriggerEvidenceSource.COMMON,
                    TriggerEvidenceStage.REARM_STEP_FAILED,
                    detail = "trigger=${trigger.name} steps=${report.failedSteps.joinToString(",") { it.name }}",
                )
            }
        }
        return report
    }

    /** Last re-arm passes, newest last: `epochMs|summary`. */
    fun recentReports(): List<String> = prefs.getString(KEY_LOG, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun persist(report: RearmReport) {
        val lines = (recentReports() + "${report.startedAtMs}|${report.summary()}").takeLast(MAX_LOG)
        prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    private companion object {
        const val TAG = "JarvisAlarms"
        const val PREFS = "jarvis_rearm_report"
        const val KEY_LOG = "log"
        const val MAX_LOG = 20
    }
}
