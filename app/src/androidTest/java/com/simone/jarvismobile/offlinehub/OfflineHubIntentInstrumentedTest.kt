package com.simone.jarvismobile.offlinehub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simone.jarvismobile.core.offlinehub.ExternalApp
import com.simone.jarvismobile.core.offlinehub.GeoPoint
import com.simone.jarvismobile.core.offlinehub.IntentSpec
import com.simone.jarvismobile.core.offlinehub.KiwixIntents
import com.simone.jarvismobile.core.offlinehub.LaunchOutcome
import com.simone.jarvismobile.core.offlinehub.OrganicMapsLinks
import com.simone.jarvismobile.core.offlinehub.RouteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BETA 6 — the intents JARVIS builds, against the REAL Android Uri and PackageManager on an emulator.
 * A stand-in activity (instrumentation manifest only) declares the same kinds of intent filters Organic Maps and Kiwix
 * declare, so we can prove our intents are well-formed and routable. This does NOT prove that the real Organic Maps or
 * Kiwix handle them: that is the Honor 200 checklist. The emulator has neither app installed, which is also what
 * lets us test the "not installed" path for real.
 */
@RunWith(AndroidJUnit4::class)
class OfflineHubIntentInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val me: String = InstrumentationRegistry.getInstrumentation().context.packageName

    private fun resolves(spec: IntentSpec, pkg: String = me): Boolean {
        val intent = Intent(spec.action)
        if (spec.mimeType != null) intent.setDataAndType(Uri.parse(spec.uri), spec.mimeType) else intent.data = Uri.parse(spec.uri)
        intent.setPackage(pkg)
        @Suppress("DEPRECATION")
        // the test APK's own PackageManager: it can always see its own stand-in activity
        return InstrumentationRegistry.getInstrumentation().context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }

    @Test fun neitherExternalAppIsInstalledOnTheEmulatorAndTheLauncherSaysSo() {
        val launcher = ExternalAppLauncher(context)
        ExternalApp.entries.forEach { app ->
            assertTrue(launcher.installedPackages(app).isEmpty())
            assertEquals(LaunchOutcome.NotInstalled(app), launcher.openMain(app, null))
            assertEquals(LaunchOutcome.NotInstalled(app), launcher.launchFirstHandled(app, null, "x", build = { emptyList() }))
        }
    }

    @Test fun organicMapsLinksAreWellFormedAndRoutable() {
        val rome = GeoPoint(41.9028, 12.4964); val milan = GeoPoint(45.4642, 9.19)
        val route = OrganicMapsLinks.route(rome, "Posizione attuale", milan, "Duomo di Milano", RouteMode.WALK, null)
        val u = Uri.parse(route.uri)
        assertEquals("om", u.scheme); assertEquals("route", u.host)
        assertEquals("41.902800,12.496400", u.getQueryParameter("sll"))
        assertEquals("Posizione attuale", u.getQueryParameter("saddr"))
        assertEquals("45.464200,9.190000", u.getQueryParameter("dll"))
        assertEquals("Duomo di Milano", u.getQueryParameter("daddr"))
        assertEquals("pedestrian", u.getQueryParameter("type"))
        assertTrue(resolves(route))
        OrganicMapsLinks.search("farmacia h24 & caffè", null).forEach { assertTrue(it.uri, resolves(it)) }
        assertEquals("farmacia h24 & caffè", Uri.parse(OrganicMapsLinks.search("farmacia h24 & caffè", null)[0].uri).getQueryParameter("query"))
        OrganicMapsLinks.show(rome, "Colosseo (Roma)", null).forEach { assertTrue(it.uri, resolves(it)) }
        assertEquals("41.902800,12.496400", Uri.parse(OrganicMapsLinks.show(rome, "Colosseo", null)[0].uri).getQueryParameter("ll"))
    }

    @Test fun kiwixIntentsAreRoutableAndTargetTheChosenPackageOnly() {
        assertTrue(resolves(KiwixIntents.openZim("content://com.android.externalstorage.documents/document/primary%3AZim%2Fwiki.zim", null)))
        // targeted at a package that does not exist (or is not installed): nothing resolves, so nothing is launched
        assertFalse(resolves(KiwixIntents.openZim("content://x/wiki.zim", "org.kiwix.kiwixmobile"), pkg = "org.kiwix.kiwixmobile"))
        assertFalse(resolves(OrganicMapsLinks.search("bar", null)[0], pkg = "app.organicmaps"))
    }

    @Test fun anIntentNobodyHandlesIsReportedAsNotHandledNotCrashed() {
        val unhandled = IntentSpec("android.intent.action.VIEW", "om-unknown://nothing", null)
        assertFalse(resolves(unhandled))
        assertNotNull(Uri.parse(unhandled.uri))
    }
}
