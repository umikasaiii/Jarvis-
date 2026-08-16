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

    /** See [JarvisNotificationListener.hasActiveCall] — no telephony permission involved. */
    fun hasActiveCall(): Boolean = JarvisNotificationListener.instance?.hasActiveCall() ?: false

    /**
     * One card per sender, not per raw notification — "Marco · 2 messaggi"
     * (spec §9), not two separate Marco cards. Pulls more raw notifications
     * than [limit] before grouping so a busy conversation does not crowd out
     * other senders; [DrivingNotification.count] is the real per-sender count,
     * never a placeholder.
     */
    fun snapshot(limit: Int = 8): List<DrivingNotification> {
        val listener = JarvisNotificationListener.instance ?: return emptyList()
        return listener.snapshot(limit = RAW_PULL_LIMIT)
            .groupBy { it.packageName to it.title }
            .map { (_, items) ->
                val latest = items.maxBy { it.postedAt }
                DrivingNotification(
                    id = latest.key,
                    app = appLabel(latest.packageName),
                    sender = latest.title,
                    preview = latest.text,
                    count = items.size,
                    postedAtEpochMs = latest.postedAt,
                    supportsReply = latest.supportsReply,
                )
            }
            .sortedByDescending { it.postedAtEpochMs }
            .take(limit)
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

    private companion object {
        /** JarvisNotificationListener.snapshot() itself caps at 20. */
        const val RAW_PULL_LIMIT = 20
    }
}
