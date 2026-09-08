package com.simone.jarvismobile.navigation

import android.content.Context
import android.util.Log
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
import com.simone.jarvismobile.core.navigation.RerouteCooldown
import com.simone.jarvismobile.core.navigation.Route
import com.simone.jarvismobile.core.navigation.RouteGenerationGate
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
 * What happened to one route/reroute computation, for diagnostics only (§
 * JARVIS Implementation Master Plan PASSAGGIO 9 §8/§9) — never itself a
 * second source of truth: [NavigationRepository.navState]/`route` remain
 * authoritative, this only labels the *attempt* that produced (or failed to
 * produce, or lost the race to produce) the current state.
 */
enum class RouteComputationOutcome { ACCEPTED, STALE_DISCARDED, FAILED, CANCELLED }

/**
 * Bounded, privacy-safe snapshot of the last route-computation attempt (§9)
 * — no coordinates, no route geometry, no destination text, only counts and
 * labels. Exists so a future device-acceptance check (or a diagnostics
 * panel, not built here — §13) can tell generation/adapter/outcome apart
 * without re-deriving them from log lines.
 */
data class NavigationDiagnostic(
    val generation: Long,
    val adapterUsed: String?,
    val outcome: RouteComputationOutcome,
    val routePointCount: Int? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * The navigation session shared by the UI and (future) foreground service, built
 * on the pure `:core` engine: GNSS, offline map coverage, deterministic routing,
 * map matching, off-route detection, live progress and spoken instructions.
 *
 * Location is only collected while [start]ed (battery §15). Routing is
 * offline-first and never depends on the AI model; instructions come only
 * from the computed route (§6, §19). When the offline engine has no data for
 * the area — increasingly the common case now that JARVIS Drive's map itself
 * can render from online tiles without any region installed — and a TomTom
 * key is saved, [onlineRoutingEngine] computes the route instead of leaving
 * navigation unusable (`docs/PRIVACY.md`); with no key saved it simply
 * returns null and the offline failure message stands.
 */
@Singleton
class NavigationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: NavigationLocationProvider,
    private val regionStore: InstalledRegionStore,
    private val routingEngine: NavigationEngine,
    private val onlineRoutingEngine: TomTomRoutingEngine,
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

    /** § PASSAGGIO 9 — which route/reroute attempt last touched [route]/[navState], and how. */
    private val _diagnostic = MutableStateFlow<NavigationDiagnostic?>(null)
    val diagnostic: StateFlow<NavigationDiagnostic?> = _diagnostic.asStateFlow()

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
    private val rerouteCooldown = RerouteCooldown()

    /**
     * § PASSAGGIO 9 — the single generation owner for every route/reroute
     * this repository computes. [routeJob] is cancelled whenever a newer
     * request/reroute/stop supersedes it — a real latency win — but
     * correctness never depends on that cancellation actually landing: every
     * publish site below re-checks [routeGate] right before it would touch
     * [_route]/[_navState] regardless of whether the job was cancellable.
     */
    private val routeGate = RouteGenerationGate()
    private var routeJob: Job? = null

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
        // § PASSAGGIO 9 §1/§2 — a new request supersedes whatever was
        // outstanding: bump the generation first (so a check racing this
        // call already sees the new owner), THEN cancel the old job as a
        // latency optimisation only, never as the correctness mechanism.
        if (routeJob?.isActive == true) {
            setDiagnostic(routeGate.current(), null, RouteComputationOutcome.CANCELLED, null)
        }
        val myGeneration = routeGate.beginGeneration()
        routeJob?.cancel()
        _navState.value = machine.dispatch(NavEvent.SearchStarted)
        routeJob = scope.launch {
            val region = _coveringRegion.value
            when (val r = routingEngine.calculateRoute(region, from, dest, prof, opts)) {
                is RoutingResult.Success -> publishRoute(myGeneration, r.route, ADAPTER_OFFLINE)
                is RoutingResult.Failure -> {
                    val online = onlineRouteFallback(from, dest, prof)
                    if (online != null) {
                        publishRoute(myGeneration, online, ADAPTER_ONLINE)
                    } else {
                        publishFailure(myGeneration, "Percorso non disponibile offline per questa zona (${r.error}).")
                    }
                }
            }
        }
    }

    /**
     * Online routing fallback — active automatically whenever a TomTom key
     * is saved (same account as live traffic/search), no separate toggle.
     * Null when no key is saved or the request itself fails — the caller
     * then shows the honest offline-failure message instead of a fake route.
     */
    private suspend fun onlineRouteFallback(from: LatLng, dest: LatLng, prof: RoutingProfile): Route? =
        onlineRoutingEngine.calculateRoute(from, dest, prof)

    fun pauseNavigation() { _navState.value = machine.dispatch(NavEvent.Pause) }
    fun resumeNavigation() { _navState.value = machine.dispatch(NavEvent.Resume) }

    fun stopNavigation() {
        val wasActive = routeJob?.isActive == true
        val supersededGeneration = routeGate.current()
        routeJob?.cancel()
        routeJob = null
        // § PASSAGGIO 9 §3 — invalidates every outstanding generation
        // atomically with clearing the session below: a publish racing this
        // call either lands fully first, or sees the bumped epoch and is
        // discarded — never a torn result.
        routeGate.stop {
            destination = null
            matcher = null
            progressCalc = null
            _route.value = null
            _progress.value = null
            announcer.reset()
            offRoute.reset()
            rerouteCooldown.reset()
            _navState.value = machine.dispatch(NavEvent.Stop)
        }
        if (wasActive) setDiagnostic(supersededGeneration, null, RouteComputationOutcome.CANCELLED, null)
        runCatching { NavigationService.stop(context) }
    }

    /**
     * Publishes [route] as the active/current route only if [generation] is
     * still the one [routeGate] currently accepts (§1) — an older generation
     * finishing late, even one whose [routeJob] escaped cancellation, is
     * discarded here rather than overwriting whatever is current now.
     */
    private fun publishRoute(generation: Long, route: Route, adapter: String) {
        val accepted = routeGate.publishIfCurrent(generation) {
            _route.value = route
            val m = MapMatcher(route)
            matcher = m
            progressCalc = RouteProgressCalculator(route, m)
            offRoute.reset()
            rerouteCooldown.reset()
            announcer.reset()
            _navState.value = machine.dispatch(NavEvent.RouteFound)
            _navState.value = machine.dispatch(NavEvent.StartNavigation)
        }
        setDiagnostic(
            generation, adapter,
            if (accepted) RouteComputationOutcome.ACCEPTED else RouteComputationOutcome.STALE_DISCARDED,
            route.geometry.size,
        )
        if (accepted) {
            // Keep guidance alive with the screen off (spec §14).
            runCatching { NavigationService.start(context) }
        } else {
            Log.w(TAG, "stale_route_discarded generation=$generation current=${routeGate.current()}")
        }
    }

    /**
     * A genuine computation failure for [generation] — discarded exactly
     * like a success would be if a newer request has since superseded it
     * (§1/§8: a stale failure must not overwrite a since-accepted route
     * with an error message, nor mark a superseded session as failed).
     */
    private fun publishFailure(generation: Long, message: String) {
        val accepted = routeGate.publishIfCurrent(generation) {
            _navState.value = machine.dispatch(NavEvent.RouteFailed)
            _message.value = message
        }
        setDiagnostic(
            generation, null,
            if (accepted) RouteComputationOutcome.FAILED else RouteComputationOutcome.STALE_DISCARDED,
            null,
        )
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
        if ((_navState.value == NavState.NAVIGATING || _navState.value == NavState.ARRIVING) &&
            route != null && m != null && calc != null
        ) {
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
            // ARRIVING: close enough to show it, not yet close enough to declare it.
            if (_navState.value == NavState.NAVIGATING && prog.remainingDistanceMeters <= ARRIVING_THRESHOLD_M) {
                _navState.value = machine.dispatch(NavEvent.Approaching)
            }

            // Spoken instruction, anticipated by speed and de-duplicated.
            prog.nextManeuver?.let { mv ->
                val idx = route.maneuvers.indexOf(mv)
                announcer.onProgress(idx, mv, prog.distanceToManeuverMeters, (fix.speedMps ?: 0f).toDouble())
                    ?.let { announce(it) }
            }

            // Off-route → recalculate offline, gated by a cooldown (spec §19) so a
            // still-poor fix right after a recalculation can't retrigger instantly.
            if (offRoute.update(match, fix) && rerouteCooldown.canReroute(fix.timestampMs)) {
                _navState.value = machine.dispatch(NavEvent.OffRouteConfirmed)
                rerouteCooldown.markRerouted(fix.timestampMs)
                announce(announcer.recalculating())
                recalculate(fix.location)
            }
        }
    }

    /**
     * A reroute is its own new generation (§4): it supersedes whatever the
     * initial search or a previous reroute left outstanding, exactly like
     * [startNavigation] supersedes a prior search. On failure the still-usable
     * current route is deliberately left untouched (only [_message] is set) —
     * existing policy already never fabricates a replacement, which this pass
     * preserves rather than changes.
     */
    private fun recalculate(from: LatLng) {
        val dest = destination ?: return
        val myGeneration = routeGate.beginGeneration()
        routeJob?.cancel()
        routeJob = scope.launch {
            when (val r = routingEngine.recalculateRoute(_coveringRegion.value, from, dest, profile, options)) {
                is RoutingResult.Success -> publishRecalculatedRoute(myGeneration, r.route, ADAPTER_OFFLINE)
                is RoutingResult.Failure -> {
                    val online = onlineRouteFallback(from, dest, profile)
                    if (online != null) {
                        publishRecalculatedRoute(myGeneration, online, ADAPTER_ONLINE)
                    } else {
                        // § PASSAGGIO 9 §4 — a failed reroute never fabricates
                        // a route nor clears the current one; only the
                        // transient message and diagnostics record it.
                        setDiagnostic(myGeneration, null, RouteComputationOutcome.FAILED, null)
                        _message.value = "Ricalcolo non riuscito (${r.error})."
                    }
                }
            }
        }
    }

    private fun publishRecalculatedRoute(generation: Long, route: Route, adapter: String) {
        val accepted = routeGate.publishIfCurrent(generation) {
            _route.value = route
            val m = MapMatcher(route)
            matcher = m
            progressCalc = RouteProgressCalculator(route, m)
            offRoute.reset()
            announcer.reset()
            _navState.value = machine.dispatch(NavEvent.RecalculationDone)
        }
        setDiagnostic(
            generation, adapter,
            if (accepted) RouteComputationOutcome.ACCEPTED else RouteComputationOutcome.STALE_DISCARDED,
            route.geometry.size,
        )
        if (!accepted) {
            Log.w(TAG, "stale_reroute_discarded generation=$generation current=${routeGate.current()}")
        }
    }

    private fun setDiagnostic(
        generation: Long,
        adapter: String?,
        outcome: RouteComputationOutcome,
        routePointCount: Int?,
    ) {
        _diagnostic.value = NavigationDiagnostic(generation, adapter, outcome, routePointCount)
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
        const val TAG = "JarvisNavigation"
        const val WEAK_ACCURACY_M = 40f
        const val ARRIVE_THRESHOLD_M = 25.0
        const val ARRIVING_THRESHOLD_M = 150.0
        const val ADAPTER_OFFLINE = "offline_astar"
        const val ADAPTER_ONLINE = "online_tomtom"
    }
}
