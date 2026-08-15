package com.simone.jarvismobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.simone.jarvismobile.audio.ListeningService
import com.simone.jarvismobile.automation.AutomationRepository
import com.simone.jarvismobile.automation.PlaceRepository
import com.simone.jarvismobile.background.JarvisNotifications
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Registers the notification channel used by the
 * user-started listening foreground service (docs/ARCHITECTURE.md §9), and
 * re-arms the automation engine on every cold start.
 *
 * Arming here, not only on boot or when a screen opens, is what keeps the engine
 * alive after an OEM force-stop: the next time the process starts for any
 * reason, the time alarms and location geofences are rebuilt from the files.
 */
@HiltAndroidApp
class JarvisApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createListeningChannel()
        JarvisNotifications.createChannels(this)
        armAutomations()
    }

    private fun armAutomations() {
        appScope.launch {
            runCatching {
                val deps = EntryPointAccessors.fromApplication(
                    this@JarvisApplication,
                    StartupEntryPoint::class.java,
                )
                // Places first, so their geofences are cached before the
                // automations that reference them re-sync.
                deps.places().reload()
                deps.automations().reload()
            }.onFailure { Log.w("JarvisApplication", "arm_failed ${it.javaClass.simpleName}") }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StartupEntryPoint {
        fun automations(): AutomationRepository
        fun places(): PlaceRepository
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
