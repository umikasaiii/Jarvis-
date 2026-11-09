package com.simone.jarvismobile.offlinehub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.core.offlinehub.AppDetection
import com.simone.jarvismobile.core.offlinehub.DestinationFix
import com.simone.jarvismobile.core.offlinehub.ExternalApp
import com.simone.jarvismobile.core.offlinehub.GeoPoint
import com.simone.jarvismobile.core.offlinehub.HubCommand
import com.simone.jarvismobile.core.offlinehub.KiwixIntents
import com.simone.jarvismobile.core.offlinehub.LaunchMessages
import com.simone.jarvismobile.core.offlinehub.LaunchOutcome
import com.simone.jarvismobile.core.offlinehub.MapsProvider
import com.simone.jarvismobile.core.offlinehub.IntentSpec
import com.simone.jarvismobile.core.offlinehub.OrganicMapsPlanner
import com.simone.jarvismobile.core.offlinehub.OrganicPlan
import com.simone.jarvismobile.core.offlinehub.OrganicRequest
import com.simone.jarvismobile.core.offlinehub.RouteMode
import com.simone.jarvismobile.core.offlinehub.ZimEntry
import com.simone.jarvismobile.core.offlinehub.ZimPolicy
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kiwix + Organic Maps hand-over for chat commands, the Library screen and the driving commands (BETA 6).
 * Everything here is an Android Intent to an app the user installed: no network call, no online lookup of places, no download.
 * Destinations get coordinates only from saved JARVIS places or coordinates the user typed; otherwise the text is
 * left to Organic Maps' own offline search — coordinates are never invented.
 */
@Singleton
class OfflineHubService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launcher: ExternalAppLauncher,
    private val settings: SettingsRepository,
    private val places: PlaceRepository,
    private val zim: ZimLibrary,
) {
    private val _openLibraryRequest = MutableStateFlow(0)
    /** Bumped by a voice/chat command so the UI brings the Library screen forward. */
    val openLibraryRequest: StateFlow<Int> = _openLibraryRequest.asStateFlow()
    fun requestOpenLibrary() { _openLibraryRequest.value += 1 }

    private val _openNavigatorRequest = MutableStateFlow(0)
    val openNavigatorRequest: StateFlow<Int> = _openNavigatorRequest.asStateFlow()

    // --- Kiwix ----------------------------------------------------------------

    suspend fun openKiwix(): LaunchOutcome = launcher.openMain(ExternalApp.KIWIX, settings.hubKiwixPreferred.first())

    /** Opens one archive in Kiwix; if this Kiwix version does not take it, opens Kiwix and explains how to pick it. */
    suspend fun openZim(entry: ZimEntry): LaunchOutcome {
        val preferred = settings.hubKiwixPreferred.first()
        val direct = launcher.launchFirstHandled(
            ExternalApp.KIWIX, preferred, "l'apertura diretta di un archivio",
            build = { pkg -> listOf(KiwixIntents.openZim(entry.uri, pkg)) }, grantRead = true,
        )
        if (direct is LaunchOutcome.NotHandled) {
            val main = launcher.openMain(ExternalApp.KIWIX, preferred)
            return if (main is LaunchOutcome.Launched) main.copy(note = KiwixIntents.OPEN_ZIM_FALLBACK) else main
        }
        return if (direct is LaunchOutcome.Launched) {
            direct.copy(note = "Ho chiesto a Kiwix di aprire «${entry.name}». Se non si apre da solo, scegli l'archivio nella libreria di Kiwix.")
        } else direct
    }

    /** Best-effort text search through Android's PROCESS_TEXT, which Kiwix declares. Its result is NOT verified. */
    suspend fun kiwixSearch(query: String): LaunchOutcome {
        val q = query.trim()
        if (q.isEmpty()) return LaunchOutcome.Invalid("Cosa devo cercare in Kiwix?")
        val preferred = settings.hubKiwixPreferred.first()
        val pkgOutcome = launcher.detect(ExternalApp.KIWIX, preferred)
        val pkg = when (pkgOutcome) {
            AppDetection.NotInstalled -> return LaunchOutcome.NotInstalled(ExternalApp.KIWIX)
            is AppDetection.Choose -> return LaunchOutcome.NeedsChoice(ExternalApp.KIWIX, pkgOutcome.packages)
            is AppDetection.Single -> pkgOutcome.packageId
        }
        val intent = android.content.Intent(KiwixIntents.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            setPackage(pkg)
            putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, q)
            putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        @Suppress("DEPRECATION")
        val handled = runCatching { context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() }.getOrDefault(false)
        if (!handled) {
            val main = launcher.openMain(ExternalApp.KIWIX, preferred)
            return if (main is LaunchOutcome.Launched) main.copy(note = "Questa versione di Kiwix non accetta la ricerca da JARVIS: ho aperto Kiwix, cerca «$q» nella sua barra di ricerca.") else main
        }
        return runCatching { context.startActivity(intent); LaunchOutcome.Launched(pkg, "Ho passato «$q» a Kiwix. Il risultato dipende dall'archivio aperto in Kiwix; JARVIS non lo legge.") }
            .getOrElse { LaunchOutcome.Failed(it.javaClass.simpleName) }
    }

    // --- Organic Maps ------------------------------------------------------------

    suspend fun openOrganicMaps(): LaunchOutcome = launcher.openMain(ExternalApp.ORGANIC_MAPS, settings.hubOrganicPreferred.first())

    /** Plans and launches an Organic Maps request with the documented intents. [note] is what to tell the user. */
    suspend fun organic(request: OrganicRequest): LaunchOutcome {
        val preferred = settings.hubOrganicPreferred.first()
        if (request == OrganicRequest.Open) {
            val o = openOrganicMaps()
            return if (o is LaunchOutcome.Launched) o.copy(note = (OrganicMapsPlanner.plan(request, null) as OrganicPlan.OpenOnly).message) else o
        }
        // the plan is package-agnostic text; the package is bound when the variant is known
        val firstPlan = OrganicMapsPlanner.plan(request, null)
        if (firstPlan is OrganicPlan.Refused) return LaunchOutcome.Invalid(firstPlan.reason)
        if (firstPlan !is OrganicPlan.Launch) return LaunchOutcome.Invalid("Richiesta non valida.")
        val message = firstPlan.message
        val outcome = launcher.launchFirstHandled(
            ExternalApp.ORGANIC_MAPS, preferred, firstPlan.what,
            build = { pkg -> (OrganicMapsPlanner.plan(request, pkg) as OrganicPlan.Launch).candidates },
        )
        if (outcome is LaunchOutcome.Launched) return outcome.copy(note = message)
        return outcome
    }

    /** "Portami a X con Organic Maps": coordinates only from a saved place / typed pair, origin only if already known. */
    suspend fun organicRoute(destination: String, mode: RouteMode, label: String? = null): LaunchOutcome {
        val fix = DestinationFix.of(destination, savedPlace(destination))
        val from = lastKnownLocation()
        val req = when (fix) {
            is DestinationFix.Verified -> OrganicRequest.Route(fix.point, label?.takeIf { it.isNotBlank() } ?: fix.label, mode, from)
            is DestinationFix.TextOnly -> OrganicRequest.Route(null, fix.text, mode, from)
        }
        return organic(req)
    }

    // --- Provider-aware driving ----------------------------------------------------

    /** Opens whichever maps app the user chose for driving. The choice is never changed here. */
    suspend fun openPreferredMaps(): LaunchOutcome = when (settings.hubMapsProvider.first()) {
        MapsProvider.ORGANIC_MAPS -> openOrganicMaps()
        MapsProvider.GOOGLE_MAPS ->
            if (com.simone.jarvismobile.driving.DrivingMapsLauncher.openMaps(context)) LaunchOutcome.Launched(null) else LaunchOutcome.Failed("maps")
        MapsProvider.JARVIS_INTERNAL -> { _openNavigatorRequest.value += 1; LaunchOutcome.Launched(null, "Apro il navigatore interno di JARVIS.") }
    }

    /** Driving "imposta navigazione per X": shows the destination in the chosen app without starting guidance. */
    suspend fun drivingPreview(query: String, label: String): LaunchOutcome = when (settings.hubMapsProvider.first()) {
        MapsProvider.GOOGLE_MAPS ->
            if (com.simone.jarvismobile.driving.DrivingMapsLauncher.previewDestination(context, query)) LaunchOutcome.Launched(null) else LaunchOutcome.Failed("maps")
        MapsProvider.ORGANIC_MAPS -> when (val fix = DestinationFix.of(query, null)) {
            is DestinationFix.Verified -> organic(OrganicRequest.Show(fix.point, label.takeIf { it.isNotBlank() }))
            is DestinationFix.TextOnly -> organic(OrganicRequest.Search(fix.text))
        }
        MapsProvider.JARVIS_INTERNAL -> { _openNavigatorRequest.value += 1; LaunchOutcome.Launched(null, "Apro il navigatore interno: di' «portami a $label» per avviare il percorso.") }
    }

    /** Driving "avvia percorso": starts guidance where the app supports it, otherwise says what it did instead. */
    suspend fun drivingStart(query: String, label: String): LaunchOutcome = when (settings.hubMapsProvider.first()) {
        MapsProvider.GOOGLE_MAPS ->
            if (com.simone.jarvismobile.driving.DrivingMapsLauncher.startNavigation(context, query)) LaunchOutcome.Launched(null) else LaunchOutcome.Failed("maps")
        MapsProvider.ORGANIC_MAPS -> organicRoute(query, RouteMode.CAR, label)
        MapsProvider.JARVIS_INTERNAL -> { _openNavigatorRequest.value += 1; LaunchOutcome.Launched(null, "Apro il navigatore interno: di' «portami a $label» per avviare il percorso.") }
    }

    suspend fun mapsProvider(): MapsProvider = settings.hubMapsProvider.first()
    suspend fun setMapsProvider(p: MapsProvider) = settings.setHubMapsProvider(p)

    // --- chat commands --------------------------------------------------------------

    /** Executes a recognised command and returns the sentence JARVIS says. Never touches the network. */
    suspend fun handle(cmd: HubCommand): String = when (cmd) {
        HubCommand.OpenKiwix -> LaunchMessages.text(openKiwix().let { if (it is LaunchOutcome.Launched) it.copy(note = "Apro Kiwix. Gli archivi che hai scaricato si consultano lì, anche senza Internet.") else it })
        HubCommand.OpenLibrary -> { requestOpenLibrary(); "Apro la Biblioteca offline." }
        is HubCommand.KiwixSearch -> LaunchMessages.text(kiwixSearch(cmd.query))
        is HubCommand.ZimQuestion -> ZimPolicy.NOT_QUERYABLE
        HubCommand.OpenOrganicMaps -> LaunchMessages.text(organic(OrganicRequest.Open))
        is HubCommand.OrganicSearch -> LaunchMessages.text(organic(OrganicRequest.Search(cmd.query)))
        is HubCommand.OrganicShow -> {
            // local copy: `point` comes from another module, so it cannot be smart-cast
            val point = cmd.point
            if (point == null) "Coordinate non valide («${cmd.rawCoordinates}»): la latitudine va da -90 a 90, la longitudine da -180 a 180."
            else LaunchMessages.text(organic(OrganicRequest.Show(point, null)))
        }
        is HubCommand.OrganicRoute -> LaunchMessages.text(organicRoute(cmd.destination, cmd.mode))
        HubCommand.OpenOfflineNavigator -> when (settings.hubMapsProvider.first()) {
            MapsProvider.ORGANIC_MAPS -> LaunchMessages.text(openOrganicMaps())
            else -> { _openNavigatorRequest.value += 1; "Apro il navigatore offline di JARVIS." }
        }
    }

    // --- helpers ---------------------------------------------------------------------

    private suspend fun savedPlace(text: String): Pair<GeoPoint, String>? {
        val id = text.trim().lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "_").trim('_')
        val p = runCatching { places.byId(id) }.getOrNull() ?: return null
        return GeoPoint(p.latitude, p.longitude) to p.displayName
    }

    /** The last position Android already has, only if location permission was granted before. No new request. */
    private fun lastKnownLocation(): GeoPoint? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        // older than an hour is not "where I am now"
        if (System.currentTimeMillis() - loc.time > 60 * 60 * 1000L) return null
        return GeoPoint(loc.latitude, loc.longitude).takeIf { it.isValid }
    }
}
