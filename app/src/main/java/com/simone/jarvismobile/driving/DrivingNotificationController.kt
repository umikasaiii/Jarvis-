package com.simone.jarvismobile.driving

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.simone.jarvismobile.core.driving.DrivingNotification
import com.simone.jarvismobile.tools.JarvisNotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The driving overlay's read of real Android notifications — no second
 * notification system, just [JarvisNotificationListener] reshaped into the
 * compact line the overlay renders. WhatsApp is the priority app (spec §7),
 * but nothing here is WhatsApp-specific: any package the listener can see
 * appears the same way, so adding another app later needs no new code path.
 */
@Singleton
class DrivingNotificationController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Ticks whenever the system posts or removes a notification. */
    val changeTick: StateFlow<Long> = JarvisNotificationListener.changeTick

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun snapshot(limit: Int = 8): List<DrivingNotification> {
        val listener = JarvisNotificationListener.instance ?: return emptyList()
        return listener.snapshot(limit = limit).map { s ->
            DrivingNotification(
                id = s.key,
                app = appLabel(s.packageName),
                sender = s.title,
                preview = s.text,
                count = 1,
                postedAtEpochMs = s.postedAt,
                supportsReply = s.supportsReply,
            )
        }
    }

    /** Never claims success Android did not actually report (spec §9). */
    fun reply(notificationId: String, text: String): Boolean =
        JarvisNotificationListener.instance?.reply(notificationId, text) ?: false

    private fun appLabel(packageName: String): String = when (packageName) {
        "com.whatsapp" -> "WhatsApp"
        else -> runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}
