package com.simone.jarvismobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.navigation.GpsFix
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.NavState
import com.simone.jarvismobile.core.navigation.NavigationProgress
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.core.navigation.Route
import com.simone.jarvismobile.core.navigation.RouteOptions
import com.simone.jarvismobile.navigation.GpsStatus
import com.simone.jarvismobile.navigation.InstalledRegionStore
import com.simone.jarvismobile.navigation.NavigationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the NavigationScreen: GNSS position, offline coverage and GPS quality. */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val repository: NavigationRepository,
    private val regionStore: InstalledRegionStore,
) : ViewModel() {

    val fix: StateFlow<GpsFix?> = repository.fix
    val gpsStatus: StateFlow<GpsStatus> = repository.gpsStatus
    val navState: StateFlow<NavState> = repository.navState
    val coveringRegion: StateFlow<RegionMetadata?> = repository.coveringRegion
    val route: StateFlow<Route?> = repository.route
    val progress: StateFlow<NavigationProgress?> = repository.progress
    val message: StateFlow<String?> = repository.message
    val openScreenRequest: StateFlow<Int> = repository.openScreenRequest
    val voiceMuted: StateFlow<Boolean> = repository.voiceMuted
    fun setVoiceMuted(value: Boolean) = repository.setVoiceMuted(value)
    val regions: StateFlow<List<RegionMetadata>> =
        repository.regions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun hasLocationPermission(): Boolean = repository.hasLocationPermission()
    fun gpsEnabled(): Boolean = repository.gpsEnabled()

    /** Absolute PMTiles path of the covering region, for the map source. */
    fun coveringPmtilesPath(): String? = coveringRegion.value?.let { regionStore.pmtilesPath(it) }

    fun start() = repository.start()
    fun stop() = repository.stop()

    fun navigateTo(dest: LatLng, options: RouteOptions = RouteOptions()) =
        repository.startNavigation(dest, options)
    fun stopNavigation() = repository.stopNavigation()

    fun refresh() = viewModelScope.launch { repository.refreshRegions() }
}
