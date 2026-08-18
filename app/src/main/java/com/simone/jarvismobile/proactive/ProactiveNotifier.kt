package com.simone.jarvismobile.proactive

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.core.proactive.ProactiveKind
import com.simone.jarvismobile.core.proactive.ProactiveSuggestion
import com.simone.jarvismobile.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a proactive suggestion as one discreet "Suggerimenti" notification with a
 * "Non avvisarmi più di questo" action, so the user can keep what they like and
 * mute a category straight from the message (spec). One id per kind, so muting or
 * a repeat replaces rather than stacks.
 */
@Singleton
class ProactiveNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun show(suggestion: ProactiveSuggestion) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            suggestion.kind.ordinal,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mute = PendingIntent.getBroadcast(
            context,
            1000 + suggestion.kind.ordinal,
            Intent(context, ProactiveActionReceiver::class.java)
                .setAction(ProactiveActionReceiver.ACTION_MUTE)
                .putExtra(ProactiveActionReceiver.EXTRA_KIND, suggestion.kind.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A digest (morning/evening) is content the user actually asked to be
        // told, not an optional nudge — it belongs on the same normal, audible
        // channel as agenda reminders and automation results, not the deliberately
        // muted "Suggerimenti" channel that BATTERY_BEFORE_ALARM and future tip-like
        // kinds stay on.
        val isDigest = suggestion.kind == ProactiveKind.MORNING_DIGEST ||
            suggestion.kind == ProactiveKind.EVENING_DIGEST
        val notification = JarvisNotifications.styled(
            context = context,
            channelId = if (isDigest) JarvisNotifications.CHANNEL_REMINDERS else JarvisNotifications.CHANNEL_SUGGESTIONS,
            title = "JARVIS",
            text = suggestion.message,
            contentIntent = open,
            expandableText = suggestion.message,
        )
            .addAction(0, "Non avvisarmi più di questo", mute)
            .setPriority(if (isDigest) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(suggestion.kind), notification)
        }
    }

    companion object {
        private const val NOTIFICATION_BASE = 7_200
        fun notificationId(kind: ProactiveKind) = NOTIFICATION_BASE + kind.ordinal
    }
}
