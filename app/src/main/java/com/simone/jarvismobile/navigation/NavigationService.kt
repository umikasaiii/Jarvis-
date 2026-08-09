package com.simone.jarvismobile.navigation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.simone.jarvismobile.R
import com.simone.jarvismobile.core.navigation.ManeuverType
import com.simone.jarvismobile.core.navigation.NavState
import com.simone.jarvismobile.core.navigation.NavigationProgress
import com.simone.jarvismobile.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Keeps offline navigation alive with the screen off (spec §14): a foreground
 * service of type location, with a persistent notification showing the next
 * maneuver, its distance and the ETA. It only runs during an active trip and
 * stops itself the moment the trip ends, so nothing costly lingers (§15). The
 * service reads the shared [NavigationRepository]; it never starts a second
 * location or routing stack.
 */
@AndroidEntryPoint
class NavigationService : Service() {

    @Inject lateinit var repository: NavigationRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForegroundCompat(buildNotification(null, NavState.NAVIGATING))
        scope.launch {
            combine(repository.progress, repository.navState) { p, s -> p to s }.collect { (p, s) ->
                if (s == NavState.IDLE || s == NavState.ARRIVED || repository.route.value == null) {
                    stopSelf()
                    return@collect
                }
                notify(buildNotification(p, s))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notify(notification: android.app.Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }

    private fun buildNotification(progress: NavigationProgress?, state: NavState): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (state) {
            NavState.RECALCULATING -> "Ricalcolo percorso…"
            NavState.GPS_WEAK -> "Segnale GPS debole"
            NavState.PAUSED -> "Navigazione in pausa"
            else -> "Navigazione in corso"
        }
        val text = progress?.let {
            val maneuver = it.nextManeuver
            val turn = maneuverText(maneuver?.type) + (maneuver?.roadName?.takeIf(String::isNotBlank)?.let { r -> " su $r" } ?: "")
            "Tra ${formatDistance(it.distanceToManeuverMeters)}: $turn · ETA ${formatMinutes(it.etaSeconds)}"
        } ?: "Avvio…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Navigazione", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Istruzioni di navigazione mentre lo schermo è spento"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun maneuverText(type: ManeuverType?): String = when (type) {
        ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT, ManeuverType.KEEP_LEFT -> "a sinistra"
        ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.KEEP_RIGHT -> "a destra"
        ManeuverType.UTURN -> "inversione a U"
        ManeuverType.ROUNDABOUT -> "alla rotonda"
        ManeuverType.ARRIVE -> "arrivo"
        else -> "prosegui dritto"
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) String.format(Locale.ITALY, "%.1f km", meters / 1000.0) else "${(meters / 10).toInt() * 10} m"

    private fun formatMinutes(seconds: Double): String {
        val m = (seconds / 60).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_navigation"
        private const val NOTIF_ID = 7100

        fun start(context: Context) {
            val intent = Intent(context, NavigationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
