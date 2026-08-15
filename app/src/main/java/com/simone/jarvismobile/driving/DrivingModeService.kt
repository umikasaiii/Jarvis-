package com.simone.jarvismobile.driving

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.simone.jarvismobile.R
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.core.driving.DrivingNotification
import com.simone.jarvismobile.core.driving.toDrivingVoiceState
import com.simone.jarvismobile.ui.MainActivity
import com.simone.jarvismobile.ui.driving.DrivingMediaPanel
import com.simone.jarvismobile.ui.driving.DrivingNavPanel
import com.simone.jarvismobile.ui.driving.DrivingTopPanel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service hosting Modalità Guida (spec §3, §20): a persistent
 * notification (mic/overlay use must always be visible, docs/PRIVACY.md), the
 * three overlay windows, and the wiring between them and the app's one real
 * voice/notification/media pipeline — see [com.simone.jarvismobile.audio.WakeWordController],
 * [DrivingNotificationController], [DrivingMediaController]. Nothing here
 * duplicates those systems.
 */
@AndroidEntryPoint
class DrivingModeService : LifecycleService() {

    @Inject lateinit var drivingModeManager: DrivingModeManager
    @Inject lateinit var coordinator: SessionCoordinator
    @Inject lateinit var notifications: DrivingNotificationController
    @Inject lateinit var media: DrivingMediaController
    @Inject lateinit var parkingGate: DrivingParkingGate

    private var topWindow: DrivingOverlayWindow? = null
    private var navWindow: DrivingOverlayWindow? = null
    private var mediaWindow: DrivingOverlayWindow? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        goForeground()
        showOverlays()
        DrivingMapsLauncher.openMaps(this)
        openConnectivitySettingsIfNeeded()
        media.start()
        drivingModeManager.onServiceStarted()

        lifecycleScope.launch {
            coordinator.state.collectLatest { drivingModeManager.setVoiceState(it.toDrivingVoiceState()) }
        }
        lifecycleScope.launch {
            notifications.changeTick.collectLatest { drivingModeManager.updateNotifications(notifications.snapshot()) }
        }
        lifecycleScope.launch {
            media.media.collectLatest { drivingModeManager.updateMedia(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) gracefulStop()
        return START_NOT_STICKY
    }

    private fun gracefulStop() {
        if (stopping) return
        stopping = true
        lifecycleScope.launch {
            runCatching { parkingGate.saveIfAway() }
            runCatching { drivingModeManager.restorePreviousMode() }
            stopSelf()
        }
    }

    override fun onDestroy() {
        hideOverlays()
        media.stop()
        drivingModeManager.onServiceStopped()
        super.onDestroy()
    }

    private fun showOverlays() {
        topWindow = DrivingOverlayWindow(this, DrivingOverlayWindow.GRAVITY_TOP, keepScreenOn = true).apply {
            show {
                val state by drivingModeManager.state.collectAsStateWithLifecycle()
                DrivingTopPanel(
                    voiceState = state.voiceState,
                    hasLocationPermission = hasFineLocation(),
                    notifications = state.notifications,
                    expandedPanel = state.expandedPanel,
                    onToggleMessages = { drivingModeManager.togglePanel(DrivingExpandedPanel.MESSAGES) },
                    onRead = { readAloud(it) },
                    onReply = { promptReply(it) },
                )
            }
        }
        navWindow = DrivingOverlayWindow(this, DrivingOverlayWindow.GRAVITY_TOP).apply {
            show {
                val state by drivingModeManager.state.collectAsStateWithLifecycle()
                state.navigation?.let { nav ->
                    DrivingNavPanel(navigation = nav, modifier = Modifier.padding(top = 92.dp, start = 10.dp))
                }
            }
        }
        mediaWindow = DrivingOverlayWindow(this, DrivingOverlayWindow.GRAVITY_BOTTOM).apply {
            show {
                val state by drivingModeManager.state.collectAsStateWithLifecycle()
                DrivingMediaPanel(
                    media = state.media,
                    art = media.art(),
                    queue = media.queueTitles(),
                    expandedPanel = state.expandedPanel,
                    onToggle = { drivingModeManager.togglePanel(DrivingExpandedPanel.MEDIA) },
                    onPrevious = media::previous,
                    onToggleTransport = media::toggle,
                    onNext = media::next,
                )
            }
        }
    }

    private fun hideOverlays() {
        topWindow?.hide(); topWindow = null
        navWindow?.hide(); navWindow = null
        mediaWindow?.hide(); mediaWindow = null
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Speaks a read-aloud message through the existing TTS path — no second voice output. */
    private fun readAloud(n: DrivingNotification) {
        lifecycleScope.launch {
            runCatching { coordinator.speakBackgroundResponse("${n.sender} dice: ${n.preview}") }
        }
    }

    /** Touch "RISPONDI": primes a normal voice session — never fakes typing or sending. */
    private fun promptReply(n: DrivingNotification) {
        lifecycleScope.launch {
            runCatching { coordinator.speakBackgroundResponse("Cosa vuoi rispondere a ${n.sender}?") }
            runCatching { coordinator.runSession() }
        }
    }

    private fun openConnectivitySettingsIfNeeded() {
        // Never claims to have flipped a radio JARVIS cannot actually touch (no
        // public API since Android 8) — it only opens the real settings surface
        // when location or any internet-capable network looks off (spec §2.7),
        // once per session start.
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationOff = locationManager?.let { runCatching { !it.isLocationEnabled }.getOrDefault(false) } ?: false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val hasInternet = cm?.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!locationOff && hasInternet) return
        val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun goForeground() {
        createChannel()
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DrivingModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.driving_mode_notification_title))
            .setContentText(getString(R.string.driving_mode_notification_text))
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .addAction(0, getString(R.string.driving_mode_stop_action), stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            getString(R.string.driving_mode_channel_name),
            android.app.NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.driving_mode_channel_desc)
            setShowBadge(false)
        }
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "jarvis_driving_mode"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.simone.jarvismobile.action.DRIVING_STOP"
    }
}
