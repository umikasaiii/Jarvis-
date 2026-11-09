package com.simone.jarvismobile.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.core.reliability.NotificationGate
import com.simone.jarvismobile.core.reliability.NotificationGateResult
import com.simone.jarvismobile.ui.MainActivity

/** What the platform reported for one reminder post. [POSTED] only means `notify()` returned; it is not proof the user saw it. */
enum class ReminderPostOutcome { POSTED, BLOCKED_PERMISSION, BLOCKED_APP_DISABLED, BLOCKED_CHANNEL, EMPTY_TEXT, API_THREW }

/**
 * The one place a reminder notification is built and posted (used by [com.simone.jarvismobile.alarms.AlarmReceiver]
 * for the on-time alarm and by the re-arm pass for reminders missed while the phone was off).
 *
 * Tapping the body opens the Attività screen for the entry; the single shade action ticks it off. The notification is
 * posted under a per-reminder *tag* with a fixed id: the previous `6000 + hash & 0xfff` id space had only 4096 values,
 * so two reminders could share an id and one silently replaced the other.
 */
object ReminderNotifier {
    const val NOTIFICATION_ID = 6000

    fun post(
        context: Context,
        id: String,
        entryId: String,
        text: String,
        subText: String,
        missed: Boolean = false,
    ): ReminderPostOutcome {
        if (text.isBlank()) return ReminderPostOutcome.EMPTY_TEXT
        val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val gate = NotificationGate.evaluate(
            runtimePermissionRequired = permissionRequired,
            permissionGranted = !permissionRequired ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            notificationsEnabledForApp = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(true),
            channelBlocked = channelBlocked(context),
        )
        when (gate) {
            NotificationGateResult.BLOCKED_PERMISSION -> return ReminderPostOutcome.BLOCKED_PERMISSION
            NotificationGateResult.BLOCKED_APP_DISABLED -> return ReminderPostOutcome.BLOCKED_APP_DISABLED
            NotificationGateResult.BLOCKED_CHANNEL -> return ReminderPostOutcome.BLOCKED_CHANNEL
            NotificationGateResult.ALLOWED -> Unit
        }
        val tag = "jarvis.reminder.$id"
        val open = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_AGENDA, true)
                .putExtra(MainActivity.EXTRA_AGENDA_ENTRY_ID, entryId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = JarvisNotifications.styled(
            context = context,
            channelId = JarvisNotifications.CHANNEL_REMINDERS,
            title = if (missed) "Promemoria mancato" else "Promemoria JARVIS",
            text = text,
            contentIntent = open,
            expandableText = text,
            withChatAction = false,
        )
            .setSubText(subText.takeIf { it.isNotBlank() })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (entryId.isNotBlank()) {
            val done = Intent(context, ReminderActionReceiver::class.java)
                .setAction(ReminderActionReceiver.ACTION_DONE)
                .putExtra(ReminderActionReceiver.EXTRA_ENTRY_ID, entryId)
                .putExtra(ReminderActionReceiver.EXTRA_NOTIF_TAG, tag)
            builder.addAction(
                0,
                "Segna come completato",
                PendingIntent.getBroadcast(context, id.hashCode() + 1, done, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        return try {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, builder.build())
            ReminderPostOutcome.POSTED
        } catch (e: Throwable) {
            ReminderPostOutcome.API_THREW
        }
    }

    /** Cancels the notification posted for [id] — used when the same entry is completed from elsewhere. */
    fun cancel(context: Context, id: String) {
        runCatching { NotificationManagerCompat.from(context).cancel("jarvis.reminder.$id", NOTIFICATION_ID) }
    }

    private fun channelBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.getNotificationChannel(JarvisNotifications.CHANNEL_REMINDERS)?.importance == NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(false)
    }
}
