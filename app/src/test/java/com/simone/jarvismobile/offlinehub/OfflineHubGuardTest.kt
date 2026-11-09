package com.simone.jarvismobile.offlinehub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BETA 6/8 — source-level guards (JVM). They pin what the hand-over code is wired to and what it cannot do (no network,
 * no geocoder, no install, no copying of archives, no AI reading of ZIM); they do not prove Kiwix/Organic Maps behave.
 */
class OfflineHubGuardTest {
    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found from ${File(".").absolutePath}")
    private val repoRoot: File = generateSequence(root.absoluteFile) { it.parentFile }.first { File(it, "core/src/main/kotlin").isDirectory }
    private val manifest: File = File(repoRoot, "app/src/main/AndroidManifest.xml")

    private fun src(rel: String) = File(root, rel).also { check(it.isFile) { "missing $rel" } }.readText()
    private fun hubFiles() = (File(root, "offlinehub").walkTopDown() + File(root, "ui/offlinehub").walkTopDown()).filter { it.isFile && it.extension == "kt" }.toList()

    private val network = Regex("""okhttp3|HttpURLConnection|java\.net\.URL\b|URLConnection|OkHttpClient|retrofit|WebView|geocod|nominatim|google\.com/maps|maps\.googleapis""", RegexOption.IGNORE_CASE)

    @Test fun theHubCodeHasNoNetworkApiAndNoGeocoder() {
        val files = hubFiles()
        assertTrue(files.size >= 5)
        assertTrue(files.filter { network.containsMatchIn(it.readText()) }.map { it.name }.toString(), files.none { network.containsMatchIn(it.readText()) })
    }

    @Test fun neverInstallsAppsAndNeverAsksForBroadStorage() {
        val code = hubFiles().joinToString("\n") { it.readText() }
        listOf("ACTION_INSTALL_PACKAGE", "REQUEST_INSTALL_PACKAGES", "market://", "play.google.com", "ACTION_VIEW, Uri.parse(\"http").forEach { assertFalse(it, it in code) }
        val m = manifest.readText()
        assertFalse("MANAGE_EXTERNAL_STORAGE" in m); assertFalse("REQUEST_INSTALL_PACKAGES" in m)
    }

    @Test fun zimArchivesAreNeverCopiedUnpackedOrFullyRead() {
        val z = src("offlinehub/ZimLibrary.kt")
        listOf("copyTo", "FileOutputStream", "openOutputStream", "writeBytes", "readBytes", "readText", "ZipInputStream", "copyToPrivate").forEach { assertFalse(it, it in z) }
        assertTrue("FLAG_GRANT_READ_URI_PERMISSION" in z); assertFalse("FLAG_GRANT_WRITE_URI_PERMISSION" in z)
        assertTrue("ByteArray(4)" in z) // only the 4-byte magic number is read
    }

    @Test fun theLauncherOnlyUsesDocumentedIntentsAndPackageVisibility() {
        val m = manifest.readText()
        listOf("org.kiwix.kiwixmobile", "org.kiwix.kiwixmobile.standalone", "app.organicmaps", "app.organicmaps.web").forEach {
            assertTrue(it, "<package android:name=\"$it\" />" in m)
        }
        val l = src("offlinehub/ExternalAppLauncher.kt")
        assertTrue("queryIntentActivities" in l && "getLaunchIntentForPackage" in l)
        assertFalse("startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(\"http" in l)
    }

    @Test fun anAiQuestionAboutAZimGetsTheHonestAnswerNotARagCall() {
        val s = src("offlinehub/OfflineHubService.kt")
        assertTrue("is HubCommand.ZimQuestion -> ZimPolicy.NOT_QUERYABLE" in s)
        val hubCode = hubFiles().joinToString("\n") { it.readText() }
        listOf("DocumentRetrieval", "KnowledgeSearch", "KnowledgeIndexer", "GroundedPrompt").forEach { assertFalse("ZIM must not feed the RAG: $it", it in hubCode) }
    }

    @Test fun chatCommandsAreRoutedBeforeTheEnginesAndPreserveTheOtherPaths() {
        val c = src("audio/SessionCoordinator.kt")
        val gen = c.substringAfter("private suspend fun generateAnswer").substringBefore("private suspend fun offlineHubAnswer")
        assertTrue(gen.indexOf("offlineHubAnswer(transcript)") in 0 until gen.indexOf("documentAnswer(transcript)"))
        assertTrue(gen.indexOf("documentAnswer(transcript)") in 0 until gen.indexOf("engineRouter.route"))
        val body = c.substringAfter("private suspend fun offlineHubAnswer").substringBefore("private suspend fun documentAnswer")
        assertFalse("tools." in body || "memory." in body || "agenda." in body)
    }

    @Test fun drivingCommandsFollowTheChosenProviderAndGoogleMapsIsStillAnOption() {
        val t = src("tools/DrivingTools.kt")
        assertTrue("hub.drivingPreview" in t && "hub.drivingStart" in t)
        assertFalse("DrivingMapsLauncher.previewDestination(context" in t) // goes through the provider now
        assertTrue(File(root, "driving/DrivingMapsLauncher.kt").isFile) // Google option kept
        val hub = src("offlinehub/OfflineHubService.kt")
        assertTrue("MapsProvider.GOOGLE_MAPS ->" in hub && "MapsProvider.ORGANIC_MAPS ->" in hub && "MapsProvider.JARVIS_INTERNAL ->" in hub)
        // the provider is only ever changed by an explicit user action, never by the launchers
        assertFalse("setHubMapsProvider" in src("offlinehub/ExternalAppLauncher.kt"))
        assertTrue("fromStored" in File(repoRoot, "core/src/main/kotlin/com/simone/jarvismobile/core/offlinehub/KiwixZim.kt").readText())
    }

    @Test fun theInternalNavigatorAndOfflineMapsScreensAreStillThere() {
        val app = src("ui/JarvisApp.kt")
        listOf("Overlay.NAVIGATION ->", "Overlay.MAPS ->", "Overlay.LIBRARY ->").forEach { assertTrue(it, it in app) }
        assertTrue(File(root, "ui/navigation/NavigationScreen.kt").isFile && File(root, "navigation/NavigationRepository.kt").isFile)
        assertTrue("OrganicMapsCard()" in src("ui/navigation/MapsScreen.kt"))
        assertTrue("onOpenLibrary" in src("ui/settings/SettingsScreen.kt"))
    }

    @Test fun roomSchemaIsUnchangedByThisStep() = assertTrue("version = 15" in src("background/JarvisDatabase.kt"))
}
