package com.simone.jarvismobile.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.simone.jarvismobile.R
import com.simone.jarvismobile.widget.JarvisIntents

/**
 * The one place JARVIS notifications are shaped. Channels (Risposte, Promemoria,
 * Sistema/background) live here, and [styled] applies the shared JARVIS look —
 * the monochrome JARVIS small icon, "JARVIS" title, dark accent and, where
 * relevant, the Apri chat / Parla / Stop actions — so every surface is
 * consistent and no launch logic is duplicated (it reuses [JarvisIntents]).
 */
object JarvisNotifications {
    const val CHANNEL_PROCESSING = "jarvis_processing"
    const val CHANNEL_RESPONSES = "jarvis_responses"
    const val CHANNEL_REMINDERS = "jarvis_reminders"
    const val CHANNEL_SUGGESTIONS = "jarvis_suggestions"

    /** JARVIS cyan, used as the notification accent where Android allows it. */
    private const val ACCENT = 0xFF4FD1E0.toInt()

    /**
     * A notification pre-styled as JARVIS. [contentIntent] is what a tap opens
     * (usually the chat). [expandableText], when given, becomes a BigTextStyle so
     * long replies expand. [withVoiceAction]/[withStopAction] add the Parla/Stop
     * buttons; [withChatAction] adds an explicit "Apri chat".
     */
    fun styled(
        context: Context,
        channelId: String,
        title: String = "JARVIS",
        text: String,
        contentIntent: PendingIntent? = null,
        expandableText: String? = null,
        withChatAction: Boolean = false,
        withVoiceAction: Boolean = false,
        withStopAction: Boolean = false,
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setColor(ACCENT)
            .setColorized(false)
            .setContentTitle(title)
            .setContentText(text)
        // No large icon: the notification shows only the JARVIS glyph (small icon),
        // not a second image on the right.
        contentIntent?.let { builder.setContentIntent(it).setAutoCancel(true) }
        expandableText?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        if (withChatAction) {
            builder.addAction(R.drawable.ic_tile_jarvis, "Apri chat", JarvisIntents.chatPending(context))
        }
        if (withVoiceAction) {
            builder.addAction(R.drawable.ic_tile_jarvis, "Parla", JarvisIntents.voicePending(context))
        }
        if (withStopAction) {
            builder.addAction(R.drawable.ic_tile_jarvis, "Stop", JarvisIntents.stopPending(context))
        }
        return builder
    }

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_PROCESSING,
                    context.getString(R.string.processing_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.processing_channel_desc)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_RESPONSES,
                    context.getString(R.string.responses_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.responses_channel_desc)
                },
                NotificationChannel(
                    CHANNEL_REMINDERS,
                    context.getString(R.string.reminders_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.reminders_channel_desc)
                },
                NotificationChannel(
                    CHANNEL_SUGGESTIONS,
                    context.getString(R.string.suggestions_channel_name),
                    NotificationManager.IMPORTANCE_LOW, // present but never intrusive
                ).apply {
                    description = context.getString(R.string.suggestions_channel_desc)
                },
            ),
        )
    }
}
