package com.simone.jarvismobile.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.simone.jarvismobile.R
import com.simone.jarvismobile.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service of type `microphone` hosting a single, user-started Phase-1
 * session (docs/ARCHITECTURE.md §9). A persistent notification makes microphone
 * use unmistakably visible and offers Parla / Interrompi / Chiudi.
 *
 * It is NEVER started automatically and there is no always-on mic. It owns the
 * mic while foregrounded and drives [SessionCoordinator]; it does not auto-restart
 * recording (`START_NOT_STICKY`).
 */
@AndroidEntryPoint
class ListeningService : Service() {

    @Inject lateinit var coordinator: SessionCoordinator

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> {
                coordinator.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_INTERRUPT -> {
                coordinator.cancel()
                return START_NOT_STICKY
            }
        }
        // The service exists to (a) make microphone use visible via the ongoing
        // notification and (b) keep the process alive if the screen turns off.
        // The actual Phase-1 session is driven by the ViewModel while the app is
        // foreground, where audio capture is always permitted.
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.listening_title))
            .setContentText(getString(R.string.listening_text))
            .setSmallIcon(R.drawable.ic_tile_jarvis)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.action_interrupt), action(ACTION_INTERRUPT))
            .addAction(0, getString(R.string.action_close), action(ACTION_STOP_SESSION))
            .build()
    }

    private fun action(name: String): PendingIntent {
        val intent = Intent(this, ListeningService::class.java).setAction(name)
        return PendingIntent.getService(
            this, name.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onDestroy() {
        runCatching { coordinator.resetAudio() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "jarvis_listening"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TALK = "com.simone.jarvismobile.action.TALK"
        const val ACTION_INTERRUPT = "com.simone.jarvismobile.action.INTERRUPT"
        const val ACTION_STOP_SESSION = "com.simone.jarvismobile.action.STOP"

        /** Must be called from a foreground context (Activity), per §7 Tile flow. */
        fun start(context: Context) {
            val intent = Intent(context, ListeningService::class.java).setAction(ACTION_TALK)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ListeningService::class.java).setAction(ACTION_STOP_SESSION),
            )
        }
    }
}
