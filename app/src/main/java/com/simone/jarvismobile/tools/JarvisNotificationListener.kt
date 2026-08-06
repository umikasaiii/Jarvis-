package com.simone.jarvismobile.tools

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * System-authorized notification bridge. Content is read only on demand and is
 * never persisted or logged; revoking Notification Access makes it unavailable
 * immediately.
 */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    data class Summary(
        val packageName: String,
        val title: String,
        val text: String,
        val postedAt: Long,
    )

    fun snapshot(packageNeedle: String? = null, limit: Int = 8): List<Summary> {
        val needle = packageNeedle?.trim()?.lowercase().orEmpty()
        return runCatching { activeNotifications.orEmpty().asSequence() }
            .getOrDefault(emptySequence())
            .filterNot { it.packageName == packageName }
            .filterNot { it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 }
            .mapNotNull(::summary)
            .filter {
                needle.isBlank() || it.packageName.lowercase().contains(needle) ||
                    it.title.lowercase().contains(needle)
            }
            .sortedByDescending { it.postedAt }
            .take(limit.coerceIn(1, 20))
            .toList()
    }

    private fun summary(sbn: StatusBarNotification): Summary? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return Summary(sbn.packageName, title.take(120), text.take(240), sbn.postTime)
    }

    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
            private set
    }
}
