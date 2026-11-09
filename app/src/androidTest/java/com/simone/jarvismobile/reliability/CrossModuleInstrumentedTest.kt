package com.simone.jarvismobile.reliability

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simone.jarvismobile.alarms.BootReceiver
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.core.reliability.RearmTrigger
import com.simone.jarvismobile.core.reliability.StepStatus
import com.simone.jarvismobile.reminders.ReminderNotifier
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BETA 8 — modules working together inside the real app process on an emulator (Hilt graph, Room, WorkManager, alarm and
 * notification services). AOSP emulator, not MagicOS; says nothing about OEM battery management.
 */
@RunWith(AndroidJUnit4::class)
class CrossModuleInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun grant() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        JarvisNotifications.createChannels(context)
    }

    @Test fun theRealRearmPassRunsEveryStepOnTheRealGraphWithoutFailureOrTimeout() = runBlocking {
        val rearmer = EntryPointAccessors.fromApplication(context.applicationContext, BootReceiver.BootEntryPoint::class.java).rearmer()
        val report = rearmer.rearm(RearmTrigger.APP_START)
        val bad = report.results.filter { it.status == StepStatus.FAILED || it.status == StepStatus.TIMED_OUT }
        assertTrue(report.summary() + " " + bad, bad.isEmpty())
        assertTrue(rearmer.recentReports().any { it.contains("trigger=APP_START") })
    }

    @Test fun aSecondRearmPassIsIdempotent() = runBlocking {
        val rearmer = EntryPointAccessors.fromApplication(context.applicationContext, BootReceiver.BootEntryPoint::class.java).rearmer()
        val first = rearmer.rearm(RearmTrigger.TIME_CHANGED)
        val second = rearmer.rearm(RearmTrigger.TIME_CHANGED)
        assertEquals(first.results.map { it.step to it.status }, second.results.map { it.step to it.status })
    }

    @Test fun notificationsFromDifferentModulesNeverReplaceEachOther() {
        val nm = context.getSystemService(NotificationManager::class.java)
        fun post(tag: String?, id: Int, text: String) {
            val n = NotificationCompat.Builder(context, JarvisNotifications.CHANNEL_REMINDERS)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("t").setContentText(text).build()
            if (tag == null) NotificationManagerCompat.from(context).notify(id, n) else NotificationManagerCompat.from(context).notify(tag, id, n)
        }
        // the navigation foreground notification (untagged, id 7100) and a backup notice (tagged, same id 7100)
        post(null, 7100, "nav"); post("jarvis.backup", 7100, "backup")
        post("jarvis.automation", 5000, "auto"); post("jarvis.response", 5000, "resp")
        ReminderNotifier.post(context, "xmod", "e", "Promemoria", "")
        val keys = nm.activeNotifications.map { it.tag to it.id }
        listOf(null to 7100, "jarvis.backup" to 7100, "jarvis.automation" to 5000, "jarvis.response" to 5000, "jarvis.reminder.xmod" to 6000)
            .forEach { assertTrue("$it in $keys", it in keys) }
        // clearing "response ready" notices by tag leaves everyone else alone
        nm.activeNotifications.filter { it.tag == "jarvis.response" }.forEach { nm.cancel(it.tag, it.id) }
        val after = nm.activeNotifications.map { it.tag to it.id }
        assertFalse(("jarvis.response" to 5000) in after)
        assertTrue((null to 7100) in after && ("jarvis.reminder.xmod" to 6000) in after && ("jarvis.automation" to 5000) in after)
        nm.cancelAll()
    }
}
