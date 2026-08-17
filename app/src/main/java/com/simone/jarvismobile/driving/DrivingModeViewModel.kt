package com.simone.jarvismobile.driving

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.core.driving.DrivingNavigationMode
import com.simone.jarvismobile.core.driving.DrivingNotification
import com.simone.jarvismobile.core.driving.DrivingUiState
import com.simone.jarvismobile.core.driving.metersPerSecondToKmh
import com.simone.jarvismobile.core.driving.toDrivingVoiceState
import com.simone.jarvismobile.core.driving.toManeuverUiModel
import com.simone.jarvismobile.core.navigation.GpsFix
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.Place
import com.simone.jarvismobile.core.navigation.PlaceHit
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.core.navigation.Route
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.navigation.InstalledRegionStore
import com.simone.jarvismobile.navigation.NavigationRepository
import com.simone.jarvismobile.navigation.OnlineMapStyleFetcher
import com.simone.jarvismobile.navigation.PlaceSearchRepository
import com.simone.jarvismobile.navigation.TomTomSearchFetcher
import com.simone.jarvismobile.navigation.TomTomTrafficFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject

/**
 * Backs [DrivingModeActivity]: composes [DrivingUiState] from the same
 * sources the existing overlay already reads — [SessionCoordinator]'s voice
 * pipeline, [NavigationRepository]'s offline GNSS/route/progress, and the
 * media/notification controllers — never a second copy of any of them.
 */
@HiltViewModel
class DrivingModeViewModel @Inject constructor(
    private val coordinator: SessionCoordinator,
    private val navigationRepository: NavigationRepository,
    private val regionStore: InstalledRegionStore,
    private val notifications: DrivingNotificationController,
    private val mediaController: DrivingMediaController,
    private val placeSearch: PlaceSearchRepository,
    private val settings: SettingsRepository,
    private val onlineMapStyleFetcher: OnlineMapStyleFetcher,
    private val trafficFetcher: TomTomTrafficFetcher,
    private val searchFetcher: TomTomSearchFetcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DrivingUiState(navigationMode = DrivingNavigationMode.INTERNAL_JARVIS_NAVIGATION))
    val uiState: StateFlow<DrivingUiState> = _uiState.asStateFlow()

    val fix: StateFlow<GpsFix?> = navigationRepository.fix
    val route: StateFlow<Route?> = navigationRepository.route

    /** Null when no installed region covers the current fix — drives the "no offline map" state. */
    val coveringRegion: StateFlow<RegionMetadata?> = navigationRepository.coveringRegion

    /**
     * The online map fallback's vector source (spec: opt-in, off by default —
     * see [SettingsRepository.onlineMapFallbackEnabled]), fetched only once
     * there is genuinely no offline coverage here and the user has turned the
     * fallback on. Null otherwise, including on fetch failure — the existing
     * "no offline map" state is always the safe default, never a fake tile.
     */
    private val _onlineSourceJson = MutableStateFlow<String?>(null)
    val onlineSourceJson: StateFlow<String?> = _onlineSourceJson.asStateFlow()

    /**
     * TomTom live-traffic vector source ([TomTomTrafficFetcher]) — active
     * automatically whenever a TomTom API key is saved ([TrafficApiKeyStore]),
     * no separate toggle. Null when no key is saved — the map then shows no
     * traffic layer at all, never a broken one.
     */
    private val _trafficSourceJson = MutableStateFlow<String?>(null)
    val trafficSourceJson: StateFlow<String?> = _trafficSourceJson.asStateFlow()

    init {
        mediaController.start()

        viewModelScope.launch {
            combine(navigationRepository.coveringRegion, settings.onlineMapFallbackEnabled) { region, enabled -> region == null && enabled }
                .collect { shouldFetch ->
                    _onlineSourceJson.value = if (shouldFetch) onlineMapStyleFetcher.vectorSourceJson() else null
                }
        }

        // No separate toggle: a saved TomTom key is itself the opt-in, so
        // traffic is on automatically whenever one is present.
        _trafficSourceJson.value = trafficFetcher.trafficSourceJson()

        viewModelScope.launch {
            coordinator.state.collect { conv ->
                _uiState.update { it.copy(voiceState = conv.toDrivingVoiceState()) }
            }
        }
        viewModelScope.launch {
            navigationRepository.route.collect { r ->
                _uiState.update { it.copy(navigationActive = r != null) }
            }
        }
        viewModelScope.launch {
            navigationRepository.progress.collect { progress ->
                _uiState.update {
                    it.copy(
                        nextManeuver = progress?.toManeuverUiModel(),
                        remainingMinutes = progress?.etaSeconds?.let { s -> (s / 60).roundToInt() },
                        remainingDistanceMeters = progress?.remainingDistanceMeters?.roundToInt(),
                        etaEpochMs = progress?.etaSeconds?.let { s -> System.currentTimeMillis() + (s * 1000).toLong() },
                    )
                }
            }
        }
        viewModelScope.launch {
            navigationRepository.fix.collect { f ->
                _uiState.update { it.copy(currentSpeedKmh = f?.speedMps?.let(::metersPerSecondToKmh)) }
            }
        }
        viewModelScope.launch {
            mediaController.media.collect { m -> _uiState.update { it.copy(media = m) } }
        }
        viewModelScope.launch {
            notifications.changeTick.collect {
                _uiState.update { s ->
                    s.withIncomingCall(notifications.hasActiveCall()).copy(messages = notifications.snapshot())
                }
            }
        }
    }

    /** Same entry point the Home mic button uses ([SessionCoordinator.startSession]) — no second mic path. */
    fun startVoiceSession() = coordinator.startSession()

    fun hasLocationPermission(): Boolean = navigationRepository.hasLocationPermission()
    fun startLocation() = navigationRepository.start()
    fun stopLocation() = navigationRepository.stop()
    fun coveringPmtilesPath(): String? =
        (navigationRepository.coveringRegion.value)?.let { regionStore.pmtilesPath(it) }

    fun navigateTo(destination: LatLng) = navigationRepository.startNavigation(destination)
    fun stopNavigation() = navigationRepository.stopNavigation()

    /**
     * Destination search for the "Places" entry point (spec §11) — the exact
     * same [PlaceSearchRepository] the voice pipeline uses, capped to a short
     * list ("non introdurre liste lunghe"). Blank query returns no results
     * rather than every known place.
     *
     * When the offline index doesn't fill the list, the remaining slots are
     * filled from [TomTomSearchFetcher]'s online geocoding — active
     * automatically whenever a TomTom key is saved, no separate toggle. A
     * different kind of exception from the traffic tiles: it sends the
     * user's typed text, not just anonymous coordinates (`docs/PRIVACY.md`).
     * Offline results always come first and are never replaced; with no key
     * saved, [TomTomSearchFetcher] itself just returns nothing.
     */
    suspend fun searchDestinations(query: String): List<PlaceHit> {
        if (query.isBlank()) return emptyList()
        val here = fix.value?.location
        val offline = placeSearch.search(query, here, limit = 5)
        if (offline.size >= 5) return offline
        val known = offline.mapTo(HashSet()) { it.place.location }
        val online = searchFetcher.search(query, here, limit = 5 - offline.size)
            .filter { it.location !in known }
            .map { PlaceHit(place = it, score = 0.0, distanceMeters = null) }
        return offline + online
    }

    /** Starts navigation to a tapped search result and records it, same as a voice destination. */
    fun navigateToPlace(place: Place) {
        navigationRepository.startNavigation(place.location)
        viewModelScope.launch { runCatching { placeSearch.addHistory(place.name, place.location) } }
    }

    /** "Importa una mappa": asks MainActivity's JarvisApp to open MapsScreen once this Activity finishes. */
    fun requestImportMap() = navigationRepository.requestOpenMapsScreen()

    fun togglePanel(panel: DrivingExpandedPanel) {
        _uiState.update { it.togglePanel(panel) }
    }

    // Pass-throughs to the same DrivingMediaController the existing overlay uses —
    // art()/queueTitles() are read fresh per call, same pattern as DrivingModeService.
    fun mediaArt() = mediaController.art()
    fun mediaQueue(): List<String> = mediaController.queueTitles()
    fun mediaPrevious() = mediaController.previous()
    fun mediaNext() = mediaController.next()
    fun mediaToggleTransport() = mediaController.toggle()

    /** Same read-aloud path as the existing overlay ([DrivingModeService]) — no second voice output. */
    fun readNotification(n: DrivingNotification) {
        viewModelScope.launch { runCatching { coordinator.speakBackgroundResponse("${n.sender} dice: ${n.preview}") } }
    }

    /** "RISPONDI": primes a normal voice session, same as the existing overlay — never fakes sending. */
    fun promptReply(n: DrivingNotification) {
        viewModelScope.launch {
            runCatching { coordinator.speakBackgroundResponse("Cosa vuoi rispondere a ${n.sender}?") }
            runCatching { coordinator.runSession() }
        }
    }

    override fun onCleared() {
        mediaController.stop()
        super.onCleared()
    }
}
