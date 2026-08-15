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
