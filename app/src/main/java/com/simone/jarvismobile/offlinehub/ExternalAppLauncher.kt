package com.simone.jarvismobile.offlinehub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.simone.jarvismobile.core.offlinehub.AppDetection
import com.simone.jarvismobile.core.offlinehub.ExternalApp
import com.simone.jarvismobile.core.offlinehub.ExternalAppDetector
import com.simone.jarvismobile.core.offlinehub.IntentSpec
import com.simone.jarvismobile.core.offlinehub.LaunchOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands work to an installed external app through a documented Intent — never installs anything, asks for no
 * storage permission, and never reaches the network itself. Visibility of the other apps comes from the
 * `<queries>` entries in the manifest (Android 11+ package visibility).
 *
 * "Launched" means the system accepted the Intent; it does not prove the other app did what we asked.
 */
@Singleton
class ExternalAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun installedPackages(app: ExternalApp): Set<String> =
        app.packages.filter { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess }.toSet()

    fun detect(app: ExternalApp, preferred: String?): AppDetection =
        ExternalAppDetector.detect(app, installedPackages(app), preferred?.takeIf { it.isNotBlank() })

    /** Just opens the app's launcher screen. */
    fun openMain(app: ExternalApp, preferred: String?): LaunchOutcome =
        when (val d = detect(app, preferred)) {
            AppDetection.NotInstalled -> LaunchOutcome.NotInstalled(app)
            is AppDetection.Choose -> LaunchOutcome.NeedsChoice(app, d.packages)
            is AppDetection.Single -> {
                val intent = context.packageManager.getLaunchIntentForPackage(d.packageId)
                if (intent == null) LaunchOutcome.NotHandled(app, "l'apertura")
                else start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), d.packageId)
            }
        }

    /**
     * Tries [candidates] in order and launches the first one the installed version actually handles.
     * [build] receives the chosen package id so the specs can be targeted at exactly that variant.
     */
    fun launchFirstHandled(
        app: ExternalApp,
        preferred: String?,
        what: String,
        build: (packageId: String) -> List<IntentSpec>,
        grantRead: Boolean = false,
    ): LaunchOutcome {
        val pkg = when (val d = detect(app, preferred)) {
            AppDetection.NotInstalled -> return LaunchOutcome.NotInstalled(app)
            is AppDetection.Choose -> return LaunchOutcome.NeedsChoice(app, d.packages)
            is AppDetection.Single -> d.packageId
        }
        for (spec in build(pkg)) {
            val intent = toIntent(spec, grantRead)
            if (!canHandle(intent)) continue
            return start(intent, pkg)
        }
        return LaunchOutcome.NotHandled(app, what)
    }

    private fun toIntent(spec: IntentSpec, grantRead: Boolean): Intent {
        val uri = Uri.parse(spec.uri)
        val intent = Intent(spec.action)
        if (spec.mimeType != null) intent.setDataAndType(uri, spec.mimeType) else intent.data = uri
        spec.packageId?.let { intent.setPackage(it) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (grantRead) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    @Suppress("DEPRECATION")
    private fun canHandle(intent: Intent): Boolean =
        runCatching { context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() }
            .getOrDefault(false)

    private fun start(intent: Intent, pkg: String): LaunchOutcome = try {
        context.startActivity(intent)
        LaunchOutcome.Launched(pkg)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "external_launch_not_found")
        LaunchOutcome.Failed("attività non trovata")
    } catch (e: SecurityException) {
        Log.w(TAG, "external_launch_denied")
        LaunchOutcome.Failed("accesso negato")
    } catch (e: Throwable) {
        Log.w(TAG, "external_launch_failed ${e.javaClass.simpleName}")
        LaunchOutcome.Failed(e.javaClass.simpleName)
    }

    private companion object { const val TAG = "JarvisExternalApp" }
}
