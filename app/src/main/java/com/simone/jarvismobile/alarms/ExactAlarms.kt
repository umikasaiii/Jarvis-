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

    fun schedule(key: String, at: LocalDateTime, extras: Map<String, String>): Boolean =
        scheduleWithOutcome(key, at, extras) != ScheduleOutcome.FAILED

    /**
     * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.3 §12. Same
     * scheduling as [schedule] — never a second code path, [schedule] simply
     * delegates here — but distinguishes exactly why, for callers that
     * persist trigger diagnostics: SCHEDULED_EXACT / the permission-missing
     * inexact fallback / a genuine [SecurityException] / any other failure,
     * never collapsed into one generic boolean.
     */
    fun scheduleWithOutcome(key: String, at: LocalDateTime, extras: Map<String, String>): ScheduleOutcome {
        val triggerAt = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = pendingIntent(key, extras, mutable = false) ?: return ScheduleOutcome.FAILED
        return try {
            if (canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                ScheduleOutcome.SCHEDULED_EXACT
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                Log.w(TAG, "exact_alarm_denied key=$key — scheduled inexactly")
                ScheduleOutcome.SCHEDULED_INEXACT_PERMISSION_MISSING
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "alarm_schedule_security_exception key=$key")
            ScheduleOutcome.SECURITY_EXCEPTION
        } catch (e: Throwable) {
            Log.w(TAG, "alarm_schedule_failed ${e.javaClass.simpleName}")
            ScheduleOutcome.FAILED
        }
    }

    /** § §12 — never collapsed into a generic failure; see [scheduleWithOutcome]. */
    enum class ScheduleOutcome { SCHEDULED_EXACT, SCHEDULED_INEXACT_PERMISSION_MISSING, SECURITY_EXCEPTION, FAILED }

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
        /**
         * § FASE 2A.8 RELEASE GATE F — the Multi-Signal Morning Coordinator's own
         * exact-alarm firings ([com.simone.jarvismobile.proactive.MorningTriggerScheduler]).
         * Deliberately its own kind, not reused from [KIND_RULE]: this fires
         * [com.simone.jarvismobile.proactive.ProactiveManager.evaluateOnUnlock]
         * directly, never a user-authored rule.
         */
        const val KIND_MORNING_BRIEFING = "morning_briefing"
        /** Which of the coordinator's signals scheduled this firing — see [KIND_MORNING_BRIEFING]. */
        const val EXTRA_TRIGGER_SOURCE = "trigger_source"
        private const val TAG = "JarvisAlarms"
    }
}
