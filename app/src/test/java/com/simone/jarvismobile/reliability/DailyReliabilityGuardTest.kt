package com.simone.jarvismobile.reliability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BETA 7 — source-level guards for the daily-reliability fixes. They pin structure (who owns what, what is bounded,
 * what is never silent). They do NOT prove that Android delivers an alarm or a notification on the Honor 200.
 */
class DailyReliabilityGuardTest {
    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found")
    private val manifest: File = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        .firstOrNull { it.isFile } ?: error("manifest not found")

    private fun src(rel: String) = File(root, rel).also { check(it.isFile) { "missing $rel" } }.readText()

    @Test fun bootReceiverUsesTheSingleIsolatedRearmPathAndAPersistentSafetyNet() {
        val b = src("alarms/BootReceiver.kt")
        assertTrue("RearmWorker.enqueue" in b)
        assertTrue(b.indexOf("RearmWorker.enqueue(") < b.indexOf("val pending = goAsync()"))
        assertTrue("withTimeoutOrNull" in b && "pending.finish()" in b && "finally" in b)
        // the old fragile sequence of reloads in one try must be gone from the receiver
        assertFalse("deps.agenda().reload()" in b)
        assertFalse("deps.proactiveScheduler().scheduleAll()" in b)
        val r = src("alarms/AlarmRearmer.kt")
        assertTrue("RearmRunner()" in r && "RearmStep.PROACTIVE" in r && "scheduleAll(rebook = true)" in r)
    }

    @Test fun zoneClockAndExactAlarmPermissionChangesRearmTheAlarms() {
        val m = manifest.readText()
        val block = m.substringAfter(".alarms.TimeChangeReceiver").substringBefore("</receiver>")
        listOf("TIMEZONE_CHANGED", "TIME_SET", "SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED").forEach { assertTrue(it, it in block) }
        assertTrue("android:exported=\"false\"" in block)
        assertTrue("rearmFrom" in src("alarms/TimeChangeReceiver.kt"))
    }

    @Test fun proactiveSchedulerStaysTheOnlyTemporalOwnerOfTheBriefing() {
        val s = src("proactive/ProactiveScheduler.kt")
        assertTrue("suspend fun scheduleAll(rebook: Boolean = false)" in s)
        assertTrue("if (!coherentSnapshotChanged && !rebook)" in s)
        assertTrue("rebook && plan != null" in s)
        // no second scheduler for the morning briefing was introduced
        val rearm = src("alarms/AlarmRearmer.kt") + src("alarms/RearmWorker.kt") + src("alarms/TimeChangeReceiver.kt")
        assertFalse("scheduleWithOutcome" in rearm)
        assertFalse("setExactAndAllowWhileIdle" in rearm)
        assertTrue("scheduleAll(rebook = true)" in src("JarvisApplication.kt"))
        // MorningTriggerScheduler remains a non-scheduling class
        assertFalse("setExact" in src("proactive/MorningTriggerScheduler.kt"))
    }

    @Test fun theBriefingAlarmHandlerIsBoundedAndAlwaysRearmsTheSuccessor() {
        val a = src("alarms/AlarmReceiver.kt")
        val morning = a.substringAfter("KIND_MORNING_BRIEFING -> {")
        assertTrue("BoundedWork.runThenAlways" in morning)
        assertTrue("PROACTIVE_CALL_TIMED_OUT" in morning)
        assertTrue(morning.indexOf("BoundedWork.runThenAlways") < morning.indexOf("scheduler.reconcileNextAlarm()"))
        assertTrue("pending.finish()" in morning)
    }

    @Test fun remindersAreNeverDroppedSilentlyAndNeverShareANotificationId() {
        val n = src("reminders/ReminderNotifier.kt")
        assertTrue("NotificationGate.evaluate" in n && "areNotificationsEnabled" in n)
        assertTrue("notify(tag, NOTIFICATION_ID" in n)
        assertFalse("0x0fff" in n)
        val a = src("alarms/AlarmReceiver.kt")
        assertTrue("ReminderNotifier.post" in a && "ReminderDeliveryLedger" in a)
        assertFalse("0x0fff" in a)
        val l = src("reminders/ReminderDeliveryLedger.kt")
        assertTrue("outcome == ReminderPostOutcome.POSTED" in l) // a blocked post is not marked delivered
    }

    @Test fun missedRemindersAreCaughtUpOnlyByARestartPassNeverByAnOrdinaryEdit() {
        val r = src("alarms/AlarmRearmer.kt")
        assertTrue("deliverMissed" in r && "catchUpMissed" in r)
        assertFalse("TIMEZONE_CHANGED" in r.substringAfter("val catchUpMissed").substringBefore("val actions"))
        val s = src("reminders/ReminderScheduler.kt")
        assertTrue("MissedReminderPolicy.select" in s)
        assertFalse("deliverMissed" in src("agenda/AgendaRepository.kt"))
        // bookings the platform refused are not remembered as scheduled
        assertTrue("val accepted = desired.filter" in s)
    }

    @Test fun automationsCollapseDuplicateAlarmsAndStayBounded() {
        val a = src("alarms/AlarmReceiver.kt")
        assertTrue("AutomationFireGuard.shouldRun" in a)
        assertTrue("ALARM_WORK_BUDGET_MS" in a)
    }

    @Test fun drivingModeStartStopIsIdempotentAndAlwaysRestoresTheMode() {
        val m = src("driving/DrivingModeManager.kt")
        assertTrue("DrivingLifecycle(JarvisModes.DRIVING)" in m)
        assertTrue("lifecycle.requestStart(" in m && "lifecycle.requestStop()" in m)
        assertTrue("scope.launch { restorePreviousMode() }" in m)
        val s = src("driving/DrivingModeService.kt")
        assertTrue("SHUTDOWN_BUDGET_MS" in s && "stopSelf()" in s)
        assertTrue("RECORD_AUDIO" in s.substringAfter("private fun goForeground"))
        assertTrue("runCatching { hideOverlays() }" in s)
    }

    @Test fun theChosenNavigatorIsStillTheOneDrivingModeOpens() {
        assertTrue("offlineHub.openPreferredMaps()" in src("driving/DrivingModeService.kt"))
        assertTrue("hub.drivingPreview" in src("tools/DrivingTools.kt"))
    }

    @Test fun localBackupDoesNotDependOnTheNetworkOnlyTheCloudCopyDoes() {
        val sch = src("backup/BackupScheduler.kt")
        assertTrue("NetworkType.NOT_REQUIRED" in sch)
        assertFalse("NetworkType.UNMETERED" in sch)
        val w = src("backup/BackupWorker.kt")
        assertTrue("CloudUploadGate.decide" in w)
        assertTrue(w.indexOf("repo.runBackup()") < w.indexOf("CloudUploadGate.decide"))
        // cloud sync stays opt-in
        assertTrue("backupCloudEnabled" in src("backup/CloudSyncManager.kt"))
    }

    @Test fun restoreStillRefusesBeforeTouchingAnythingAndKeepsASafetyBackup() {
        val r = src("backup/BackupRepository.kt")
        listOf("archive_checksum", "safety_backup", "manifest_tampered", "compatibility").forEach { assertTrue(it, it in r) }
        val safety = r.indexOf("val safetyBackup = runBackup()")
        assertTrue(safety > 0 && r.indexOf("RestoreStaging.cleanup(stagingRoot)", safety) > safety) // staging/cutover only after the safety backup
    }

    @Test fun thereIsNoCloudNoCorePcAndNoNewAndroidPrivilegeInTheNewReliabilityCode() {
        val files = listOf("alarms/AlarmRearmer.kt", "alarms/RearmWorker.kt", "alarms/BootReceiver.kt", "alarms/TimeChangeReceiver.kt",
            "reminders/ReminderNotifier.kt", "reminders/ReminderDeliveryLedger.kt")
        val bad = Regex("""okhttp3|HttpURLConnection|java\.net\.URL\b|retrofit|WebView|CorePc|core_pc""", RegexOption.IGNORE_CASE)
        files.forEach { assertFalse(it, bad.containsMatchIn(src(it))) }
        val m = manifest.readText()
        assertFalse("MANAGE_EXTERNAL_STORAGE" in m.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), ""))
        assertFalse("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in m.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), ""))
    }

    @Test fun roomSchemaIsUnchangedByThisStep() = assertTrue("version = 15" in src("background/JarvisDatabase.kt"))
}
