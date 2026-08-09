package com.simone.jarvismobile.navigation

import com.simone.jarvismobile.core.navigation.GpsFix
import com.simone.jarvismobile.core.navigation.NavEvent
import com.simone.jarvismobile.core.navigation.NavState
import com.simone.jarvismobile.core.navigation.NavigationStateMachine
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.core.navigation.RegionSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
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

/** GNSS acquisition quality, surfaced to the dashboard card and the screen. */
enum class GpsStatus { NONE, ACQUIRING, WEAK, OK }

/**
 * The navigation session state that the UI and the (future) foreground service
 * share, built on the pure `:core` engine. This stage tracks position, offline
 * map coverage and GPS quality; routing/turn-by-turn arrive with the BRouter and
 * search stages and will drive the same [NavigationStateMachine].
 *
 * Location is only collected while [start]ed, and stopped on [stop], so
 * high-accuracy GPS is never held on when no one is navigating (spec §15).
 */
@Singleton
class NavigationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: NavigationLocationProvider,
    private val regionStore: InstalledRegionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var locationJob: Job? = null
    private val machine = NavigationStateMachine()

    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix.asStateFlow()

    private val _gpsStatus = MutableStateFlow(GpsStatus.NONE)
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private val _regions = MutableStateFlow<List<RegionMetadata>>(emptyList())
    val regions: StateFlow<List<RegionMetadata>> = _regions.asStateFlow()

    private val _navState = MutableStateFlow(NavState.IDLE)
    val navState: StateFlow<NavState> = _navState.asStateFlow()

    /** The installed region covering the current position, or null (uncovered). */
    private val _coveringRegion = MutableStateFlow<RegionMetadata?>(null)
    val coveringRegion: StateFlow<RegionMetadata?> = _coveringRegion.asStateFlow()

    suspend fun refreshRegions() {
        _regions.value = regionStore.installed()
        recomputeCoverage()
    }

    fun hasLocationPermission(): Boolean = locationProvider.hasPermission()
    fun gpsEnabled(): Boolean = locationProvider.gpsEnabled()

    /** Starts GNSS collection at [intervalMs] (screen-visible cadence by default). */
    fun start(intervalMs: Long = 1_000L) {
        if (locationJob?.isActive == true) return
        _gpsStatus.value = GpsStatus.ACQUIRING
        locationJob = scope.launch {
            refreshRegions()
            locationProvider.fixes(intervalMs).collect { fix -> onFix(fix) }
        }
    }

    fun stop() {
        locationJob?.cancel()
        locationJob = null
        if (_navState.value == NavState.IDLE) _gpsStatus.value = GpsStatus.NONE
    }

    private fun onFix(fix: GpsFix) {
        _fix.value = fix
        val weak = fix.accuracyMeters > WEAK_ACCURACY_M
        _gpsStatus.value = if (weak) GpsStatus.WEAK else GpsStatus.OK
        // Feed the state machine's GPS-loss handling so an active route survives a
        // temporary weak signal instead of aborting (spec §4).
        if (weak) dispatch(NavEvent.GpsLost) else dispatch(NavEvent.GpsRestored)
        recomputeCoverage()
    }

    private fun recomputeCoverage() {
        val f = _fix.value
        _coveringRegion.value = if (f == null) null else RegionSelector.regionFor(f.location, _regions.value)
    }

    private fun dispatch(event: NavEvent) {
        _navState.value = machine.dispatch(event)
    }

    private companion object {
        /** Above this reported accuracy the fix is treated as a weak signal. */
        const val WEAK_ACCURACY_M = 40f
    }
}
