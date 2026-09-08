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
    @Inject lateinit var morningTriggerScheduler: com.simone.jarvismobile.proactive.MorningTriggerScheduler
    @Inject lateinit var ruleScheduler: com.simone.jarvismobile.automation.rule.RuleScheduler
    @Inject lateinit var placeRepository: com.simone.jarvismobile.automation.rule.PlaceRepository
    @Inject lateinit var weatherScheduler: com.simone.jarvismobile.weather.WeatherScheduler
    @Inject lateinit var eventBridgeScheduler: com.simone.jarvismobile.corebridge.EventBridgeScheduler
    @Inject lateinit var eventBridge: com.simone.jarvismobile.corebridge.EventBridge

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
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
            launch { runCatching { backupScheduler.sync() } }
            // Start the automations observer if the user turned it on (app launch is a
            // foreground-enough context to start its foreground service).
            launch { runCatching { automationServiceController.syncFromSettings() } }
            // Re-book the proactive check if the user has proactivity on.
            launch { runCatching { proactiveScheduler.sync() } }
            // § FASE 2A.8 §F — re-arm both Multi-Signal Morning Coordinator triggers
            // (NEXT_ALARM/CONFIGURED_TIME) on every cold start, exactly like
            // ruleScheduler.sync() below: an exact alarm is one-shot and does not
            // survive a reboot/force-stop on its own.
            launch { runCatching { morningTriggerScheduler.scheduleAll() } }
            // Arm the generic engine's clock triggers (phase 5). Time rules re-arm on
            // every cold start, so an OEM force-stop cannot leave the engine dead.
            launch { runCatching { ruleScheduler.sync() } }
            // Re-register place geofences from Room (phase 6). Proximity alerts do not
            // survive a reboot or a force-stop; this rebuilds them.
            launch { runCatching { placeRepository.reload() } }
            // Re-book the weather refresh if the user opted in; a harmless no-op
            // (cancels any schedule) when the setting is off.
            launch { runCatching { weatherScheduler.sync() } }
            // Event Bridge (JARVIS Core, § "fondamenta"): re-book the periodic
            // retry-flush job, a harmless no-op when Core/Event Bridge is off.
            launch { runCatching { eventBridgeScheduler.sync() } }
            // First Event Bridge producer — a low-priority, public-context signal;
            // never blocks startup (publish() is fire-and-forget).
            launch {
                runCatching {
                    eventBridge.publish(
                        com.simone.jarvismobile.core.bridge.JarvisEvent(
                            id = java.util.UUID.randomUUID().toString(),
                            type = com.simone.jarvismobile.core.bridge.JarvisEventType.APP_STARTED,
                            timestampMs = System.currentTimeMillis(),
                            source = "JarvisApplication",
                            priority = com.simone.jarvismobile.core.bridge.EventPriority.LOW,
                            privacyLevel = com.simone.jarvismobile.core.tools.SensitivityLevel.PUBLIC,
                        ),
                    )
                }
            }
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
