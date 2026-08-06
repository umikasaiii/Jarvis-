package com.simone.jarvismobile.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.simone.jarvismobile.R

object JarvisNotifications {
    const val CHANNEL_PROCESSING = "jarvis_processing"
    const val CHANNEL_RESPONSES = "jarvis_responses"
    const val CHANNEL_REMINDERS = "jarvis_reminders"

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
            ),
        )
    }
}
