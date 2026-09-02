package com.simone.jarvismobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.simone.jarvismobile.audio.ListeningService
import com.simone.jarvismobile.backup.BackupScheduler
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.widget.JarvisWidgetUpdater
import dagger.hilt.android.HiltAndroidApp
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
    @Inject lateinit var automationServiceController:
        com.simone.jarvismobile.automation.AutomationServiceController
    @Inject lateinit var proactiveScheduler: com.simone.jarvismobile.proactive.ProactiveScheduler
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
        // Keep the home-screen control widget's status in sync with the assistant.
        widgetUpdater.start()
        // Re-book the nightly backup from saved settings (survives reboots/reinstalls).
        appScope.launch { runCatching { backupScheduler.sync() } }
        // Start the automations observer if the user turned it on (app launch is a
        // foreground-enough context to start its foreground service).
        appScope.launch { runCatching { automationServiceController.syncFromSettings() } }
        // Re-book the proactive check if the user has proactivity on.
        appScope.launch { runCatching { proactiveScheduler.sync() } }
        // Arm the generic engine's clock triggers (phase 5). Time rules re-arm on
        // every cold start, so an OEM force-stop cannot leave the engine dead.
        appScope.launch { runCatching { ruleScheduler.sync() } }
        // Re-register place geofences from Room (phase 6). Proximity alerts do not
        // survive a reboot or a force-stop; this rebuilds them.
        appScope.launch { runCatching { placeRepository.reload() } }
        // Re-book the weather refresh if the user opted in; a harmless no-op
        // (cancels any schedule) when the setting is off.
        appScope.launch { runCatching { weatherScheduler.sync() } }
        // Event Bridge (JARVIS Core, § "fondamenta"): re-book the periodic
        // retry-flush job, a harmless no-op when Core/Event Bridge is off.
        appScope.launch { runCatching { eventBridgeScheduler.sync() } }
        // First Event Bridge producer — a low-priority, public-context signal;
        // never blocks startup (publish() is fire-and-forget).
        appScope.launch {
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
}
