package com.simone.jarvismobile.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Structural guards only; Android 16 API behaviour remains device-pending. */
class GuardianG2GuardTest {
    private val root = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory } ?: error("app sources missing")
    private fun src(path: String) = File(root, path).readText()

    @Test fun noUnconsentedNetworkOrPrivilege() {
        val all = src("java/com/simone/jarvismobile/guardian/DeviceGuardian.kt") +
            src("java/com/simone/jarvismobile/guardian/GuardianFileHasher.kt") +
            src("java/com/simone/jarvismobile/ui/guardian/GuardianScreen.kt")
        listOf("VpnService", "OkHttpClient", "HttpURLConnection", "AccessibilityService",
            "DevicePolicyManager", "Runtime.getRuntime", "java.net.Socket").forEach {
            assertFalse("unexpected capability: " + it, all.contains(it))
        }
        assertTrue(all.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(all.contains("GuardianPolicy.fileSizeAllowed(total)"))
        assertTrue(all.contains("MessageDigest.getInstance(\"SHA-256\")"))
    }

    @Test fun api36GuardAndManifestPermission() {
        val a = src("java/com/simone/jarvismobile/guardian/DeviceGuardian.kt")
        val m = src("AndroidManifest.xml")
        assertTrue(a.contains("Build.VERSION.SDK_INT < 36"))
        assertTrue(a.contains("android.security.advancedprotection.AdvancedProtectionManager"))
        assertTrue(m.contains("android.permission.QUERY_ADVANCED_PROTECTION_MODE"))
        assertFalse(m.contains("android.permission.QUERY_ALL_PACKAGES"))
        assertFalse(m.contains("android.permission.BIND_VPN_SERVICE"))
    }

    @Test fun realUiIsReachableAndNoFalseProtectionClaim() {
        val app = src("java/com/simone/jarvismobile/ui/JarvisApp.kt")
        val settings = src("java/com/simone/jarvismobile/ui/settings/SettingsScreen.kt")
        val ui = src("java/com/simone/jarvismobile/ui/guardian/GuardianScreen.kt")
        assertTrue(app.contains("Overlay.GUARDIAN -> GuardianScreen"))
        assertTrue(settings.contains("onOpenGuardian"))
        assertTrue(ui.contains("STATO GENERALE: NON VERIFICATO"))
        assertTrue(ui.contains("NON DISPONIBILE — G3"))
        assertTrue(ui.contains("NON DISPONIBILE — G4"))
        assertTrue(ui.contains("NON DISPONIBILE — G5"))
    }
}
