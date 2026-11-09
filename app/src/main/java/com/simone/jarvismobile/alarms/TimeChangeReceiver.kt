package com.simone.jarvismobile.alarms

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simone.jarvismobile.alarms.BootReceiver.Companion.rearmFrom
import com.simone.jarvismobile.core.reliability.RearmTrigger

/**
 * BETA 7 — alarms are absolute instants. When the time zone or the system clock changes, "07:30" no longer maps to the
 * instant that was booked, so every wall-clock alarm is recomputed. The same pass also runs when the exact-alarm
 * permission is granted or revoked, so alarms booked inexactly (permission missing) become exact — and vice versa.
 *
 * Not verified on a device: whether MagicOS delivers these broadcasts to a stopped or background-restricted app is
 * exactly the kind of fact only a physical test shows.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED -> RearmTrigger.TIMEZONE_CHANGED
            Intent.ACTION_TIME_CHANGED -> RearmTrigger.TIME_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> RearmTrigger.EXACT_ALARM_PERMISSION_CHANGED
            else -> return
        }
        rearmFrom(context, trigger)
    }
}
