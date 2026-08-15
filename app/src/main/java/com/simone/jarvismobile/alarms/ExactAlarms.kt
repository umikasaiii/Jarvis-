package com.simone.jarvismobile.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wakes the app at a wall-clock time.
 *
 * This replaces WorkManager for anything the user was promised at a specific
 * minute. WorkManager is a *deferrable* scheduler by design: `setInitialDelay`
 * promises that the work will run, not when, and Doze batches deferrable work
 * into maintenance windows. The observed result was a reminder for 11:05 that
 * arrived when the phone next woke up — and, tellingly, still did so with every
 * OEM battery exemption already granted. No setting can fix the wrong channel.
 *
 * [AlarmManager.setExactAndAllowWhileIdle] is the channel Android keeps out of
 * that batching precisely for alarms, so that is what is used here.
 *
 * A PendingIntent's identity ignores extras — action, data, type, class and
 * categories only — so every alarm gets a distinct `jarvis://alarm/<key>` data
 * URI. Without it the second alarm would silently replace the first.
 */
@Singleton
class ExactAlarms @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager by lazy { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }

    /**
     * Android 12+ can withhold exact alarms. When it does, the alarm is still
     * scheduled — inexactly — rather than dropped: late is better than never,
     * and the Settings screen can ask for the permission separately.
     */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

    fun schedule(key: String, at: LocalDateTime, extras: Map<String, String>): Boolean {
        val triggerAt = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = pendingIntent(key, extras, mutable = false) ?: return false
        return try {
            if (canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                Log.w(TAG, "exact_alarm_denied key=$key — scheduled inexactly")
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "alarm_schedule_failed ${e.javaClass.simpleName}")
            false
        }
    }

    fun cancel(key: String) {
        val pending = pendingIntent(key, emptyMap(), mutable = false) ?: return
        runCatching { manager.cancel(pending) }
        runCatching { pending.cancel() }
    }

    private fun pendingIntent(
        key: String,
        extras: Map<String, String>,
        mutable: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("jarvis://alarm/$key")
            extras.forEach { (k, v) -> putExtra(k, v) }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return runCatching {
            PendingIntent.getBroadcast(context, key.hashCode(), intent, flags)
        }.getOrNull()
    }

    companion object {
        const val ACTION_FIRE = "com.simone.jarvismobile.ALARM_FIRE"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
        /** The bare agenda entry id, so a reminder can be ticked off / opened. */
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTEXT = "subtext"
        /** New-engine time trigger: which trigger type fired, and for which occurrence. */
        const val EXTRA_TRIGGER_TYPE = "trigger_type"
        const val EXTRA_OCCURRENCE = "occurrence"
        const val KIND_REMINDER = "reminder"
        const val KIND_AUTOMATION = "automation"
        /** The generic context+automation engine's scheduled clock triggers (phase 5). */
        const val KIND_RULE = "rule"
        private const val TAG = "JarvisAlarms"
    }
}
