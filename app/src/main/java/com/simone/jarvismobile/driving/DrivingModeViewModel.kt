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
import com.simone.jarvismobile.core.navigation.Route
import com.simone.jarvismobile.navigation.InstalledRegionStore
import com.simone.jarvismobile.navigation.NavigationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(DrivingUiState(navigationMode = DrivingNavigationMode.INTERNAL_JARVIS_NAVIGATION))
    val uiState: StateFlow<DrivingUiState> = _uiState.asStateFlow()

    val fix: StateFlow<GpsFix?> = navigationRepository.fix
    val route: StateFlow<Route?> = navigationRepository.route

    init {
        mediaController.start()

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
