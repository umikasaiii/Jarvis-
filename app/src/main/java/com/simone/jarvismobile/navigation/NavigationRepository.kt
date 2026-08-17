package com.simone.jarvismobile.navigation

import android.content.Context
import com.simone.jarvismobile.core.navigation.GpsFix
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.MapMatcher
import com.simone.jarvismobile.core.navigation.NavEvent
import com.simone.jarvismobile.core.navigation.NavState
import com.simone.jarvismobile.core.navigation.NavigationProgress
import com.simone.jarvismobile.core.navigation.NavigationStateMachine
import com.simone.jarvismobile.core.navigation.OffRouteDetector
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.core.navigation.RegionSelector
import com.simone.jarvismobile.core.navigation.Route
import com.simone.jarvismobile.core.navigation.RouteOptions
import com.simone.jarvismobile.core.navigation.RouteProgressCalculator
import com.simone.jarvismobile.core.navigation.RoutingProfile
import com.simone.jarvismobile.core.navigation.RoutingResult
import com.simone.jarvismobile.core.navigation.VoiceAnnouncer
import com.simone.jarvismobile.core.mode.LocationPrecision
import com.simone.jarvismobile.mode.JarvisModeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class GpsStatus { NONE, ACQUIRING, WEAK, OK }

/**
 * The navigation session shared by the UI and (future) foreground service, built
 * on the pure `:core` engine: GNSS, offline map coverage, deterministic routing,
 * map matching, off-route detection, live progress and spoken instructions.
 *
 * Location is only collected while [start]ed (battery §15). Routing is fully
 * offline and never depends on the AI model; instructions come only from the
 * computed route (§6, §19).
 */
@Singleton
class NavigationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: NavigationLocationProvider,
    private val regionStore: InstalledRegionStore,
    private val routingEngine: OfflineRoutingEngine,
    private val placeSearch: PlaceSearchRepository,
    private val voice: NavigationVoiceController,
    private val modeManager: JarvisModeManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var locationJob: Job? = null
    private val machine = NavigationStateMachine()
    private val announcer = VoiceAnnouncer()

    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix.asStateFlow()

    private val _gpsStatus = MutableStateFlow(GpsStatus.NONE)
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private val _regions = MutableStateFlow<List<RegionMetadata>>(emptyList())
    val regions: StateFlow<List<RegionMetadata>> = _regions.asStateFlow()

    private val _navState = MutableStateFlow(NavState.IDLE)
    val navState: StateFlow<NavState> = _navState.asStateFlow()

    private val _coveringRegion = MutableStateFlow<RegionMetadata?>(null)
    val coveringRegion: StateFlow<RegionMetadata?> = _coveringRegion.asStateFlow()

    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    private val _progress = MutableStateFlow<NavigationProgress?>(null)
    val progress: StateFlow<NavigationProgress?> = _progress.asStateFlow()

    /** A transient user-facing message (announcement, recalculating, routing error). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Bumped when navigation should be brought to the foreground (open the screen). */
    private val _openScreenRequest = MutableStateFlow(0)
    val openScreenRequest: StateFlow<Int> = _openScreenRequest.asStateFlow()
    fun requestOpenScreen() { _openScreenRequest.value += 1 }

    /**
     * Bumped when the offline-maps download screen should open — the "importa
     * regione" action from JARVIS Drive's missing-map state, which runs in a
     * separate Activity ([com.simone.jarvismobile.driving.DrivingModeActivity])
     * and has no `MapsScreen` of its own to navigate to directly. Finishing
     * that Activity reveals `MainActivity` underneath, whose `JarvisApp`
     * collects this and opens the same `MapsScreen` the Impostazioni entry
     * point uses — no second maps-import UI.
     */
    private val _openMapsScreenRequest = MutableStateFlow(0)
    val openMapsScreenRequest: StateFlow<Int> = _openMapsScreenRequest.asStateFlow()
    fun requestOpenMapsScreen() { _openMapsScreenRequest.value += 1 }

    val voiceMuted: StateFlow<Boolean> get() = voice.muted
    fun setVoiceMuted(value: Boolean) = voice.setMuted(value)

    // Active-trip state.
    @Volatile private var destination: LatLng? = null
    @Volatile private var options: RouteOptions = RouteOptions()
    @Volatile private var profile: RoutingProfile = RoutingProfile.CAR
    @Volatile private var matcher: MapMatcher? = null
    @Volatile private var progressCalc: RouteProgressCalculator? = null
    private val offRoute = OffRouteDetector()

    suspend fun refreshRegions() {
        _regions.value = regionStore.installed()
        recomputeCoverage()
    }

    fun hasLocationPermission(): Boolean = locationProvider.hasPermission()
    fun gpsEnabled(): Boolean = locationProvider.gpsEnabled()

    /**
     * Starts collecting fixes. When [intervalMs] is left at its default, the
     * interval is sized from the current [JarvisModeManager] mode instead of a
     * single fixed value — DRIVING asks GPS for fixes more often, HOME/SLEEP
     * less often — so "Guida → alta precisione" (§ Location Engine power
     * modes) actually changes something during an active session, the only
     * place this app ever requests live location at all.
     */
    fun start(intervalMs: Long = intervalForCurrentMode()) {
        if (locationJob?.isActive == true) return
        _gpsStatus.value = GpsStatus.ACQUIRING
        locationJob = scope.launch {
            refreshRegions()
            locationProvider.fixes(intervalMs).collect { onFix(it) }
        }
    }

    private fun intervalForCurrentMode(): Long = when (modeManager.locationPrecision.value) {
        LocationPrecision.HIGH_PRECISION -> 500L
        LocationPrecision.BALANCED -> 1_000L
        LocationPrecision.LOW_POWER -> 2_500L
    }

    fun stop() {
        locationJob?.cancel()
        locationJob = null
        if (_navState.value == NavState.IDLE) _gpsStatus.value = GpsStatus.NONE
    }

    /**
     * Starts guidance to [dest]: computes an offline route from the current fix,
     * and on success enters NAVIGATING. On no route (e.g. the region has no routing
     * data) it posts a message and stays idle — no faked route.
     */
    fun startNavigation(dest: LatLng, opts: RouteOptions = RouteOptions(), prof: RoutingProfile = RoutingProfile.CAR) {
        val from = _fix.value?.location ?: run { _message.value = "Nessuna posizione GPS."; return }
        destination = dest; options = opts; profile = prof
        _navState.value = machine.dispatch(NavEvent.SearchStarted)
        scope.launch {
            val region = _coveringRegion.value
            when (val r = routingEngine.calculateRoute(region, from, dest, prof, opts)) {
                is RoutingResult.Success -> applyRoute(r.route)
                is RoutingResult.Failure -> {
                    _navState.value = machine.dispatch(NavEvent.RouteFailed)
                    _message.value = "Percorso non disponibile offline per questa zona (${r.error})."
                }
            }
        }
    }

    fun pauseNavigation() { _navState.value = machine.dispatch(NavEvent.Pause) }
    fun resumeNavigation() { _navState.value = machine.dispatch(NavEvent.Resume) }

    fun stopNavigation() {
        destination = null
        matcher = null
        progressCalc = null
        _route.value = null
        _progress.value = null
        announcer.reset()
        offRoute.reset()
        _navState.value = machine.dispatch(NavEvent.Stop)
        runCatching { NavigationService.stop(context) }
    }

    private fun applyRoute(route: Route) {
        _route.value = route
        val m = MapMatcher(route)
        matcher = m
        progressCalc = RouteProgressCalculator(route, m)
        offRoute.reset()
        announcer.reset()
        _navState.value = machine.dispatch(NavEvent.RouteFound)
        _navState.value = machine.dispatch(NavEvent.StartNavigation)
        // Keep guidance alive with the screen off (spec §14).
        runCatching { NavigationService.start(context) }
    }

    private fun onFix(fix: GpsFix) {
        _fix.value = fix
        val weak = fix.accuracyMeters > WEAK_ACCURACY_M
        _gpsStatus.value = if (weak) GpsStatus.WEAK else GpsStatus.OK
        if (weak) _navState.value = machine.dispatch(NavEvent.GpsLost)
        else if (_navState.value == NavState.GPS_WEAK) _navState.value = machine.dispatch(NavEvent.GpsRestored)
        recomputeCoverage()

        // Live guidance while navigating.
        val route = _route.value
        val m = matcher
        val calc = progressCalc
        if (_navState.value == NavState.NAVIGATING && route != null && m != null && calc != null) {
            val match = m.match(fix)
            val prog = calc.progress(match, fix)
            _progress.value = prog

            // Arrival.
            if (prog.remainingDistanceMeters <= ARRIVE_THRESHOLD_M) {
                _navState.value = machine.dispatch(NavEvent.Arrived)
                announce(announcer.arrival())
                runCatching { NavigationService.stop(context) }
                return
            }

            // Spoken instruction, anticipated by speed and de-duplicated.
            prog.nextManeuver?.let { mv ->
                val idx = route.maneuvers.indexOf(mv)
                announcer.onProgress(idx, mv, prog.distanceToManeuverMeters, (fix.speedMps ?: 0f).toDouble())
                    ?.let { announce(it) }
            }

            // Off-route → recalculate offline.
            if (offRoute.update(match, fix)) {
                _navState.value = machine.dispatch(NavEvent.OffRouteConfirmed)
                announce(announcer.recalculating())
                recalculate(fix.location)
            }
        }
    }

    private fun recalculate(from: LatLng) {
        val dest = destination ?: return
        scope.launch {
            when (val r = routingEngine.recalculateRoute(_coveringRegion.value, from, dest, profile, options)) {
                is RoutingResult.Success -> {
                    _route.value = r.route
                    val m = MapMatcher(r.route)
                    matcher = m
                    progressCalc = RouteProgressCalculator(r.route, m)
                    offRoute.reset()
                    announcer.reset()
                    _navState.value = machine.dispatch(NavEvent.RecalculationDone)
                }
                is RoutingResult.Failure -> {
                    _message.value = "Ricalcolo non riuscito (${r.error})."
                }
            }
        }
    }

    /** Shows a message and speaks it with the offline navigation voice. */
    private fun announce(text: String) {
        _message.value = text
        voice.speak(text)
    }

    private fun recomputeCoverage() {
        val f = _fix.value
        val region = if (f == null) null else RegionSelector.regionFor(f.location, _regions.value)
        val changed = region?.id != _coveringRegion.value?.id
        _coveringRegion.value = region
        // Warm the offline place index for the covering region (once).
        if (changed && region != null) scope.launch { placeSearch.ensurePlacesLoaded(region.id) }
    }

    private companion object {
        const val WEAK_ACCURACY_M = 40f
        const val ARRIVE_THRESHOLD_M = 25.0
    }
}
