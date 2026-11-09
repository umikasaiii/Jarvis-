package com.simone.jarvismobile.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BETA 8 — source-level guards for cross-module conflicts and the security surface. Structure only: they do not prove
 * runtime behaviour on the Honor 200.
 */
class FinalIntegrationGuardTest {
    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found")
    private val manifestText: String = (listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        .firstOrNull { it.isFile } ?: error("manifest")).readText()
    private val manifestNoComments = manifestText.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    private fun src(rel: String) = File(root, rel).readText()
    private fun allKotlin() = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun theAssistantAndTheTranslatorNeverCompeteForTheSharedRecognizer() {
        val c = src("audio/SessionCoordinator.kt")
        assertTrue("MicrophonePolicy.pauseTranslatorForAssistant(liveTranslator.isRunning)" in c)
        assertTrue("MicrophonePolicy.cancelAssistantForTranslator(machine.state.value)" in c)
    }

    @Test fun everyDynamicNotificationLivesInItsOwnNamespace() {
        // an untagged notify(id, ...) is only allowed for fixed foreground-service ids
        val untagged = Regex("""\.notify\(\s*(?!["A-Za-z_.]*TAG|tag|effectiveTag|[A-Z_]*_TAG)([^,()]+),""")
        val offenders = allKotlin().filter { f -> untagged.findAll(f.readText()).any { true } }.map { it.name }
        // NavigationService posts its foreground notification with a fixed id (7100), documented as untagged.
        assertEquals(listOf("NavigationService.kt"), offenders)
        assertTrue("jarvis.backup" in src("backup/BackupWorker.kt"))
        assertTrue("jarvis.automation" in src("automation/AutomationRunner.kt"))
        assertTrue("jarvis.response" in src("background/AssistantTaskWorker.kt"))
        // opening the chat clears response notices by tag, never by an id range
        val clear = src("audio/SessionCoordinator.kt").substringAfter("fun clearResponseNotifications()").substringBefore("/** Builds the vault")
        assertTrue("RESPONSE_NOTIFICATION_TAG" in clear)
        assertFalse("RESPONSE_NOTIFICATION_MIN" in clear)
    }

    @Test fun onlyTheEntryPointsThatMustBeExportedAreExported() {
        val exported = Regex("""<(activity|service|receiver|provider)[^>]*?android:name="([^"]+)"[^>]*?android:exported="true"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifestNoComments).map { it.groupValues[2] }.toList()
        // launcher, Health Connect privacy-rationale activity, notification listener and QS tile (both protected by a system-only permission)
        assertEquals(setOf(".ui.MainActivity", ".health.ViewPermissionUsageActivity", ".tools.JarvisNotificationListener", ".tile.JarvisTileService"), exported.toSet())
        assertTrue("BIND_NOTIFICATION_LISTENER_SERVICE" in manifestNoComments && "BIND_QUICK_SETTINGS_TILE" in manifestNoComments)
        // the boot receiver only needs system broadcasts
        assertTrue(Regex("""\.alarms\.BootReceiver"\s+android:exported="false"""").containsMatchIn(manifestNoComments))
    }

    @Test fun everyPendingIntentDeclaresItsMutability() {
        allKotlin().forEach { f ->
            val text = f.readText()
            Regex("""PendingIntent\.get(Activity|Broadcast|Service|ForegroundService)\(""").findAll(text).forEach { m ->
                val window = text.substring(m.range.first, minOf(text.length, m.range.first + 700))
                assertTrue("${f.name}: PendingIntent without FLAG_IMMUTABLE/FLAG_MUTABLE near ${m.range.first}", "FLAG_IMMUTABLE" in window || "FLAG_MUTABLE" in window || "flags" in window || "immutable" in window)
            }
        }
        // the one mutable PendingIntent (proximity alerts) targets an explicit component
        val g = src("automation/rule/PlaceGeofenceSource.kt")
        assertTrue("Intent(context, PlaceProximityReceiver::class.java)" in g)
        assertTrue(Regex("""PlaceProximityReceiver"\s+android:exported="false"""").containsMatchIn(manifestNoComments))
    }

    @Test fun geofencesAreInertWithoutTheBackgroundLocationGrantAndAreRemovedFirst() {
        val g = src("automation/rule/PlaceGeofenceSource.kt")
        assertTrue("if (hasBackgroundLocation()) places.filter { it.enabled } else emptyList()" in g)
        assertTrue(g.indexOf("removeProximityAlert") < g.indexOf("addProximityAlert"))
    }

    @Test fun noDangerousPrivilegeAndNoHiddenNetworkWasAddedToTheManifest() {
        listOf("MANAGE_EXTERNAL_STORAGE", "QUERY_ALL_PACKAGES", "REQUEST_INSTALL_PACKAGES", "BIND_ACCESSIBILITY_SERVICE", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "READ_CONTACTS", "SEND_SMS", "CALL_PHONE")
            .forEach { assertFalse(it, "android.permission.$it" in manifestNoComments) }
        assertTrue("android:allowBackup=\"false\"" in manifestNoComments || "android:allowBackup=\"true\"" !in manifestNoComments)
    }

    @Test fun logsNeverCarryUserTextFromTheModulesThisBetaTouched() {
        val files = listOf("alarms/AlarmReceiver.kt", "alarms/AlarmRearmer.kt", "alarms/BootReceiver.kt", "reminders/ReminderNotifier.kt",
            "reminders/ReminderScheduler.kt", "reminders/ReminderDeliveryLedger.kt", "backup/BackupWorker.kt")
        val risky = Regex("""Log\.[iwed]\([^)]*\$\{?(text|title|entry\.text|message|body|payload|subText|whenText)\b""")
        files.forEach { assertFalse(it, risky.containsMatchIn(src(it))) }
    }

    @Test fun roomSchemaIsStillVersion15() = assertTrue("version = 15" in src("background/JarvisDatabase.kt"))
}
