package com.simone.jarvismobile.reliability

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import com.simone.jarvismobile.alarms.AlarmReceiver
import com.simone.jarvismobile.alarms.ExactAlarms
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.reminders.ReminderDeliveryLedger
import com.simone.jarvismobile.reminders.ReminderNotifier
import com.simone.jarvismobile.reminders.ReminderPostOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * BETA 7 — real Android components on an emulator (API 34, stock AOSP-like): the platform AlarmManager keeps and drops a
 * booked alarm, and NotificationManager really holds distinct reminder notifications.
 *
 * What this does NOT prove: that the alarm FIRES on time on the Honor 200 with MagicOS battery management, that Doze
 * lets it through, that a reboot restores it, or that the notification is visible to the user. Those are physical tests.
 */
@RunWith(AndroidJUnit4::class)
class DailyReliabilityInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun grantNotifications() {
        // API 33+ runtime permission, granted through the instrumentation's shell identity (no rules dependency needed).
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    private val alarms = ExactAlarms(context)

    /** The same identity ExactAlarms uses (action + data + component); extras do not take part in it. */
    private fun existing(key: String): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ExactAlarms.ACTION_FIRE
            data = Uri.parse("jarvis://alarm/$key")
        }
        return PendingIntent.getBroadcast(context, key.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun extras(id: String) = mapOf(ExactAlarms.EXTRA_KIND to ExactAlarms.KIND_REMINDER, ExactAlarms.EXTRA_ID to id)

    @Test fun aBookedAlarmExistsAndACancelledOneIsGone() {
        val key = "beta7_alarm_a"
        alarms.cancel(key)
        assertNull(existing(key))
        val outcome = alarms.scheduleWithOutcome(key, LocalDateTime.now().plusHours(6), extras("a"))
        assertTrue(outcome.name, outcome == ExactAlarms.ScheduleOutcome.SCHEDULED_EXACT || outcome == ExactAlarms.ScheduleOutcome.SCHEDULED_INEXACT_PERMISSION_MISSING)
        assertNotNull(existing(key))
        alarms.cancel(key)
        assertNull(existing(key))
    }

    @Test fun reschedulingTheSameKeyReplacesInsteadOfDuplicating() {
        val key = "beta7_alarm_b"
        alarms.schedule(key, LocalDateTime.now().plusHours(5), extras("b"))
        alarms.schedule(key, LocalDateTime.now().plusHours(7), extras("b"))
        assertNotNull(existing(key))
        alarms.cancel(key)
        // one cancel removes it entirely: there was a single alarm under that identity, not two
        assertNull(existing(key))
    }

    @Test fun twoKeysAreTwoIndependentAlarms() {
        alarms.schedule("beta7_alarm_c1", LocalDateTime.now().plusHours(5), extras("c1"))
        alarms.schedule("beta7_alarm_c2", LocalDateTime.now().plusHours(5), extras("c2"))
        alarms.cancel("beta7_alarm_c1")
        assertNull(existing("beta7_alarm_c1"))
        assertNotNull(existing("beta7_alarm_c2"))
        alarms.cancel("beta7_alarm_c2")
    }

    @Test fun twoRemindersWhoseOldIdsWouldCollideAreBothDelivered() {
        JarvisNotifications.createChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        // Two ids with the same (hashCode & 0xfff): under the previous id scheme the second replaced the first.
        val first = "collide-0"
        val second = (1..200_000).map { "collide-$it" }.first { (it.hashCode() and 0xfff) == (first.hashCode() and 0xfff) }
        val a = ReminderNotifier.post(context, first, "e1", "Primo promemoria", "")
        val b = ReminderNotifier.post(context, second, "e2", "Secondo promemoria", "")
        assertEquals(ReminderPostOutcome.POSTED, a)
        assertEquals(ReminderPostOutcome.POSTED, b)
        val tags = nm.activeNotifications.mapNotNull { it.tag }
        assertTrue(tags.toString(), "jarvis.reminder.$first" in tags && "jarvis.reminder.$second" in tags)
        ReminderNotifier.cancel(context, first); ReminderNotifier.cancel(context, second)
    }

    @Test fun cancellingOneReminderLeavesTheOtherInTheShade() {
        JarvisNotifications.createChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        ReminderNotifier.post(context, "keep", "e1", "Resta", "")
        ReminderNotifier.post(context, "drop", "e2", "Va via", "")
        ReminderNotifier.cancel(context, "drop")
        val tags = nm.activeNotifications.mapNotNull { it.tag }
        assertTrue("jarvis.reminder.keep" in tags)
        assertFalse("jarvis.reminder.drop" in tags)
        ReminderNotifier.cancel(context, "keep")
    }

    @Test fun anEmptyReminderIsReportedNotPostedSilently() {
        assertEquals(ReminderPostOutcome.EMPTY_TEXT, ReminderNotifier.post(context, "x", "e", "  ", ""))
    }

    @Test fun theLedgerMarksOnlyAPostedReminderAsDelivered() {
        val ledger = ReminderDeliveryLedger(context)
        ledger.record("beta7_posted", ReminderPostOutcome.POSTED)
        ledger.record("beta7_blocked", ReminderPostOutcome.BLOCKED_PERMISSION)
        assertTrue("beta7_posted" in ledger.delivered())
        assertFalse("beta7_blocked" in ledger.delivered())
        assertTrue(ledger.recentLog().any { it.endsWith("|BLOCKED_PERMISSION") })
        ledger.retainOnly(emptySet())
        assertTrue(ledger.delivered().isEmpty())
    }

    @Test fun exactAlarmCapabilityIsReadableFromThePlatform() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertEquals(am.canScheduleExactAlarms(), alarms.canScheduleExact())
    }
}
