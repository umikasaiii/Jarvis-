package com.simone.jarvismobile.core.reliability

/** What the platform allowed *before* any notification call was made. Never proof the user saw it. */
enum class NotificationGateResult { ALLOWED, BLOCKED_PERMISSION, BLOCKED_APP_DISABLED, BLOCKED_CHANNEL }

/**
 * One decision for "may we post?", shared by reminders and briefings. The reminder path used to check only the
 * runtime permission and return silently; it ignored a user who had switched notifications off for the app or muted
 * the reminders channel, and recorded nothing in any case.
 */
object NotificationGate {
    fun evaluate(
        runtimePermissionRequired: Boolean,
        permissionGranted: Boolean,
        notificationsEnabledForApp: Boolean,
        channelBlocked: Boolean,
    ): NotificationGateResult = when {
        runtimePermissionRequired && !permissionGranted -> NotificationGateResult.BLOCKED_PERMISSION
        !notificationsEnabledForApp -> NotificationGateResult.BLOCKED_APP_DISABLED
        channelBlocked -> NotificationGateResult.BLOCKED_CHANNEL
        else -> NotificationGateResult.ALLOWED
    }
}
