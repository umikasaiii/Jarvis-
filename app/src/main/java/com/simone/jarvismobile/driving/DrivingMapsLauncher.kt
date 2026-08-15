package com.simone.jarvismobile.driving

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Real Google Maps only, launched by Android Intent — never the paid Maps or
 * Navigation SDK (spec §3). Targets the Maps package explicitly when it is
 * installed so this never opens a generic chooser; falls back to whatever
 * handles the URI scheme (still Maps-shaped, `geo:`/`google.navigation:`) when
 * it is not, rather than crashing.
 */
object DrivingMapsLauncher {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    fun isMapsInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(MAPS_PACKAGE, 0) }.isSuccess

    /** Just brings Maps to the front, no destination. */
    fun openMaps(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** "imposta navigazione per X": shows the destination, does NOT start turn-by-turn. */
    fun previewDestination(context: Context, query: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isMapsInstalled(context)) intent.setPackage(MAPS_PACKAGE)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** "avvia percorso": starts real turn-by-turn navigation in Maps. */
    fun startNavigation(context: Context, query: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(query)}&mode=d"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isMapsInstalled(context)) intent.setPackage(MAPS_PACKAGE)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
