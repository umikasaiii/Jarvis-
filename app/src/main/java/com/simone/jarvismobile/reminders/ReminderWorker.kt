package com.simone.jarvismobile.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.ui.MainActivity

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        val date = inputData.getString(KEY_DATE).orEmpty()
        val time = inputData.getString(KEY_TIME).orEmpty()
        val whenText = listOf(date, time).filter(String::isNotBlank).joinToString(" · ")
        val notificationId = NOTIFICATION_BASE + entryId.hashCode().and(0x0fff)
        val open = PendingIntent.getActivity(
            applicationContext,
            entryId.hashCode(),
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Quick actions handled from the shade: tick it off, or postpone an hour.
        val notification = JarvisNotifications.styled(
            context = applicationContext,
            channelId = JarvisNotifications.CHANNEL_REMINDERS,
            title = "Promemoria JARVIS",
            text = title,
            contentIntent = open,
            expandableText = title,
        )
            .setSubText(whenText)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Segna come fatto", actionPending(entryId, title, date, time, notificationId, ReminderActionReceiver.ACTION_DONE, 1))
            .addAction(0, "Riprogramma", actionPending(entryId, title, date, time, notificationId, ReminderActionReceiver.ACTION_SNOOZE, 2))
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // The permission can be revoked between the check and notify().
        }
        return Result.success()
    }

    private fun actionPending(
        entryId: String,
        title: String,
        date: String,
        time: String,
        notificationId: Int,
        action: String,
        requestOffset: Int,
    ): PendingIntent {
        val intent = Intent(applicationContext, ReminderActionReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderActionReceiver.EXTRA_ENTRY_ID, entryId)
            .putExtra(ReminderActionReceiver.EXTRA_TITLE, title)
            .putExtra(ReminderActionReceiver.EXTRA_DATE, date)
            .putExtra(ReminderActionReceiver.EXTRA_TIME, time)
            .putExtra(ReminderActionReceiver.EXTRA_NOTIF_ID, notificationId)
        return PendingIntent.getBroadcast(
            applicationContext,
            entryId.hashCode() + requestOffset,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val KEY_ENTRY_ID = "entry_id"
        const val KEY_TITLE = "title"
        const val KEY_DATE = "date"
        const val KEY_TIME = "time"
        const val KEY_ALERT_KEY = "alert_key"
        private const val NOTIFICATION_BASE = 5_000
    }
}
