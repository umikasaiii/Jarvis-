package com.simone.jarvismobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.simone.jarvismobile.audio.ListeningService
import com.simone.jarvismobile.backup.BackupRepository
import com.simone.jarvismobile.backup.BackupScheduler
import com.simone.jarvismobile.backup.RestoreRecoveryOutcome
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.diagnostics.StartupDiagnostics
import com.simone.jarvismobile.diagnostics.StartupDiagnostics.Checkpoint
import com.simone.jarvismobile.widget.JarvisWidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point. Registers the notification channel used by the
 * user-started listening foreground service (docs/ARCHITECTURE.md §9).
 */
@HiltAndroidApp
class JarvisApplication : Application() {

    @Inject lateinit var widgetUpdater: JarvisWidgetUpdater
    @Inject lateinit var backupScheduler: BackupScheduler
    @Inject lateinit var backupRepository: BackupRepository
    @Inject lateinit var automationServiceController:
        com.simone.jarvismobile.automation.AutomationServiceController
    @Inject lateinit var proactiveScheduler: com.simone.jarvismobile.proactive.ProactiveScheduler
    @Inject lateinit var ruleScheduler: com.simone.jarvismobile.automation.rule.RuleScheduler
    @Inject lateinit var placeRepository: com.simone.jarvismobile.automation.rule.PlaceRepository
    @Inject lateinit var weatherScheduler: com.simone.jarvismobile.weather.WeatherScheduler
    // § JARVIS Implementation Master Plan PASSAGGIO 11 §F1 — Event Bridge
    // is architecturally DEFERRED until a concrete consumer exists
    // (jarvis-protocol defines no event-ingestion endpoint, verified
    // directly against jarvis-core's real routes). These two fields are
    // deliberately no longer used from onCreate() below — kept injected,
    // not deleted, so re-enabling is a one-line uncomment when a real
    // consumer lands, not a rewrite; see EventBridgeScheduler.sync()/
    // EventBridge.flushIfOnline(), both already fail-safe/no-op while
    // EVENT_BRIDGE_REMOTE_TRANSPORT_ENABLED stays false.
    @Inject lateinit var eventBridgeScheduler: com.simone.jarvismobile.corebridge.EventBridgeScheduler
    @Inject lateinit var eventBridge: com.simone.jarvismobile.corebridge.EventBridge

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        // § MICRO-PATCH E.1 §7/§8 — installed before anything else that
        // could throw, so the next successful startup can show what
        // happened if this one doesn't finish. Room-independent by design
        // (see StartupDiagnostics' own doc comment) — it must keep working
        // even when Room itself is what crashes.
        StartupDiagnostics.install(this)
        StartupDiagnostics.checkpoint(Checkpoint.APPLICATION_CREATED)
        super.onCreate()
        // Reaching this line means Hilt's own field-injection into this
        // Application already succeeded (it runs as part of super.onCreate()
        // above) — the one startup phase this recorder honestly cannot
        // observe if IT is what fails (§7).
        StartupDiagnostics.checkpoint(Checkpoint.HILT_READY)
        createListeningChannel()
        JarvisNotifications.createChannels(this)
        // § JARVIS Implementation Master Plan PASSAGGIO 10.2 §3 — safe to
        // start before the restore-recovery barrier below: it only observes
        // SessionCoordinator.state, a plain in-memory ConversationStateMachine
        // that never reads Room/DataStore-backed canonical state (audited;
        // pinned by WidgetUpdaterPreBarrierRegressionTest). Notification-
        // channel creation above is likewise state-independent.
        widgetUpdater.start()
        // § PASSAGGIO 10.1 §5 / PASSAGGIO 10.2 §2 — restore recovery is an
        // ORDERING BARRIER, not one coroutine racing the others below:
        // RuleScheduler/automation/place-reload/proactive/weather/backup-
        // scheduler could otherwise read or write the very same restored
        // Room DB / DataStore file before recovery finishes cutting it
        // over. PASSAGGIO 10.1 awaited the call but then launched every
        // other task regardless of what it returned — the residual gap
        // PASSAGGIO 10.2 closes: the barrier must FAIL SAFE, so startup
        // only proceeds when the explicit RestoreRecoveryOutcome says it is
        // safe to. completePendingRestoreRecovery() is a fast no-op when no
        // restore is pending, so normal startup timing is unchanged.
        appScope.launch {
            StartupDiagnostics.checkpoint(Checkpoint.RESTORE_BARRIER_STARTED)
            val outcome = try {
                backupRepository.completePendingRestoreRecovery()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // The function itself never claims success it could not
                // verify — this is the caller's own fail-safe for the case
                // it could not even determine an outcome.
                Log.w(TAG, "restore_recovery_threw ${t.javaClass.simpleName}")
                RestoreRecoveryOutcome.RECOVERY_FAILED
            }
            StartupDiagnostics.checkpoint(
                if (outcome.startupSafe) Checkpoint.RESTORE_BARRIER_OK else Checkpoint.RESTORE_BARRIER_FAILED,
            )
            if (!outcome.startupSafe) {
                // Privacy-safe: only the enum name, never file contents or
                // any restored value. Staging/markers are already left
                // exactly as BackupRepository found them for a later cold
                // start to retry — nothing here deletes anything or retries
                // in a loop.
                Log.w(TAG, "startup_gated_on_incomplete_recovery outcome=$outcome")
                return@launch
            }
            // Re-book the nightly backup from saved settings (survives reboots/reinstalls).
            launch { checkpointed(Checkpoint.BACKUP_SYNC_STARTED, Checkpoint.BACKUP_SYNC_OK, Checkpoint.BACKUP_SYNC_FAILED) { backupScheduler.sync() } }
            // Start the automations observer if the user turned it on (app launch is a
            // foreground-enough context to start its foreground service).
            launch {
                checkpointed(Checkpoint.AUTOMATION_SYNC_STARTED, Checkpoint.AUTOMATION_SYNC_OK, Checkpoint.AUTOMATION_SYNC_FAILED) {
                    automationServiceController.syncFromSettings()
                }
            }
            // Re-book the proactive check if the user has proactivity on.
            launch { checkpointed(Checkpoint.PROACTIVE_SYNC_STARTED, Checkpoint.PROACTIVE_SYNC_OK, Checkpoint.PROACTIVE_SYNC_FAILED) { proactiveScheduler.sync() } }
            // § FASE 2A.8 §F / WORK PACKAGE B §3/§16 — re-arm both canonical
            // morning triggers (NEXT_ALARM/CONFIGURED_TIME) on every cold
            // start, exactly like ruleScheduler.sync() below: an exact alarm
            // is one-shot and does not survive a reboot/force-stop on its
            // own. ProactiveScheduler is now the sole owner of this plan.
            // Shares the PROACTIVE_SYNC checkpoints above: both calls belong
            // to the same subsystem, and a failure here is equally optional.
            launch { checkpointed(Checkpoint.PROACTIVE_SYNC_STARTED, Checkpoint.PROACTIVE_SYNC_OK, Checkpoint.PROACTIVE_SYNC_FAILED) { proactiveScheduler.scheduleAll() } }
            // Arm the generic engine's clock triggers (phase 5). Time rules re-arm on
            // every cold start, so an OEM force-stop cannot leave the engine dead.
            launch { checkpointed(Checkpoint.RULE_SYNC_STARTED, Checkpoint.RULE_SYNC_OK, Checkpoint.RULE_SYNC_FAILED) { ruleScheduler.sync() } }
            // Re-register place geofences from Room (phase 6). Proximity alerts do not
            // survive a reboot or a force-stop; this rebuilds them.
            launch { checkpointed(Checkpoint.PLACE_RELOAD_STARTED, Checkpoint.PLACE_RELOAD_OK, Checkpoint.PLACE_RELOAD_FAILED) { placeRepository.reload() } }
            // Re-book the weather refresh if the user opted in; a harmless no-op
            // (cancels any schedule) when the setting is off.
            launch { checkpointed(Checkpoint.WEATHER_SYNC_STARTED, Checkpoint.WEATHER_SYNC_OK, Checkpoint.WEATHER_SYNC_FAILED) { weatherScheduler.sync() } }
            // § PASSAGGIO 11 §F1 — Event Bridge's periodic retry-flush job
            // and its APP_STARTED producer are deliberately NOT started
            // here: there is no concrete consumer for either (jarvis-core
            // has no event-ingestion endpoint), so this normal startup path
            // no longer books a WorkManager job or enqueues an event that
            // will only ever sit in local storage until it expires. See
            // eventBridgeScheduler/eventBridge's doc comments above.
        }
    }

    /**
     * § MICRO-PATCH E.1 §6/§8 — the same DEGRADED_OPTIONAL_CAPABILITY
     * discipline every subsystem below already had (`runCatching`, silently
     * continue), now also recording WHICH optional subsystem failed instead
     * of only a generic `Log.w`. `CancellationException` is never swallowed
     * — a genuine coroutine cancellation must keep propagating.
     */
    private suspend fun checkpointed(
        started: Checkpoint,
        ok: Checkpoint,
        failed: Checkpoint,
        block: suspend () -> Unit,
    ) {
        StartupDiagnostics.checkpoint(started)
        try {
            block()
            StartupDiagnostics.checkpoint(ok)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            StartupDiagnostics.checkpoint(failed)
            Log.w(TAG, "startup_subsystem_failed checkpoint=$failed exception=${t.javaClass.simpleName}")
        }
    }

    private fun createListeningChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            ListeningService.CHANNEL_ID,
            getString(R.string.listening_channel_name),
            NotificationManager.IMPORTANCE_LOW, // visible but not intrusive
        ).apply {
            description = getString(R.string.listening_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "JarvisApplication"
    }
}
