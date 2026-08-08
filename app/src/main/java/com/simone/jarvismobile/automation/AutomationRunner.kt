package com.simone.jarvismobile.automation

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.R
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.automation.Action
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries out an automation's action.
 *
 * Every branch here goes through something the user already controls: the
 * notification channel they can silence, the spoken-reply setting they can turn
 * off, the agenda they can edit. An automation is a shortcut to what JARVIS can
 * already be asked to do, never a way around a preference.
 */
@Singleton
class AutomationRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agenda: AgendaRepository,
    private val coordinator: SessionCoordinator,
) {
    suspend fun run(automation: Automation): Boolean {
        Log.i(TAG, "automation_fire id=${automation.id}")
        return when (val action = automation.action) {
            is Action.Notify -> notify(automation, action.payload)
            is Action.Speak -> {
                // Speaking obeys the background-speech preference; if it is off
                // the user still gets the message, just silently.
                val spoken = runCatching { coordinator.speakBackgroundResponse(action.payload) }
                    .getOrDefault(false)
                if (!spoken) notify(automation, action.payload) else true
            }
            is Action.AddAgenda -> agenda.add(
                AgendaEntry(date = LocalDate.now(), time = null, text = action.payload),
            )
        }
    }

    private fun notify(automation: Automation, message: String): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val open = PendingIntent.getActivity(
            context,
            automation.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, JarvisNotifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_tile_jarvis)
            .setContentTitle("Automazione JARVIS")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_BASE + automation.id.hashCode().and(0x0fff), notification)
            true
        } catch (_: SecurityException) {
            // The permission can be revoked between the check and notify().
            false
        }
    }

    private companion object {
        const val TAG = "JarvisAutomation"
        const val NOTIFICATION_BASE = 5000
    }
}
