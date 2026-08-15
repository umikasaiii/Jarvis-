package com.simone.jarvismobile.tools

import android.app.Notification
import android.app.RemoteInput
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** Bumped on any post/removal so a live consumer (Modalità Guida) can refresh. */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        _changeTick.value = System.currentTimeMillis()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        _changeTick.value = System.currentTimeMillis()
    }

    data class Summary(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val supportsReply: Boolean,
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

    /**
     * Sends [text] through the notification's own quick-reply action via
     * `RemoteInput`, exactly as if the user had typed it in the shade. Returns
     * false — never a faked success — when the notification is gone or has no
     * reply action, e.g. because the app does not support it or has already
     * been dismissed.
     */
    fun reply(key: String, text: String): Boolean {
        val sbn = runCatching { activeNotifications.orEmpty() }.getOrDefault(emptyArray())
            .firstOrNull { it.key == key } ?: return false
        val action = sbn.notification.actions.orEmpty()
            .firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return false
        val remoteInputs = action.remoteInputs ?: return false
        val bundle = Bundle()
        for (input in remoteInputs) bundle.putCharSequence(input.resultKey, text)
        val fillInIntent = android.content.Intent()
        RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, bundle)
        return runCatching { action.actionIntent.send(this, 0, fillInIntent) }.isSuccess
    }

    private fun summary(sbn: StatusBarNotification): Summary? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val supportsReply = sbn.notification.actions.orEmpty().any { !it.remoteInputs.isNullOrEmpty() }
        return Summary(sbn.key, sbn.packageName, title.take(120), text.take(240), sbn.postTime, supportsReply)
    }

    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
            private set

        private val _changeTick = MutableStateFlow(0L)

        /** Ticks on every post/removal; a live consumer collects it to refresh. */
        val changeTick: StateFlow<Long> = _changeTick.asStateFlow()
    }
}
