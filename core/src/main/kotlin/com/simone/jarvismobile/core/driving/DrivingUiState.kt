package com.simone.jarvismobile.core.driving

import com.simone.jarvismobile.core.navigation.ManeuverType
import com.simone.jarvismobile.core.navigation.NavigationProgress
import kotlin.math.roundToInt

/**
 * One instruction the ManeuverCard shows, distilled from the offline engine's
 * [com.simone.jarvismobile.core.navigation.Maneuver] plus the live distance to
 * it ([NavigationProgress.distanceToManeuverMeters]) — a UI-rounded int, never
 * a second source of truth for the maneuver itself.
 */
data class ManeuverUiModel(
    val type: ManeuverType,
    val roadName: String,
    val distanceMeters: Int,
    val roundaboutExit: Int? = null,
)

/** Builds the card model from a live [NavigationProgress] tick, or null with no upcoming turn. */
fun NavigationProgress.toManeuverUiModel(): ManeuverUiModel? {
    val maneuver = nextManeuver ?: return null
    return ManeuverUiModel(
        type = maneuver.type,
        roadName = maneuver.roadName,
        distanceMeters = distanceToManeuverMeters.roundToInt(),
        roundaboutExit = maneuver.roundaboutExit,
    )
}

/** GPS speed (m/s, as reported by Android `Location`) to the km/h the SpeedCard shows. */
fun metersPerSecondToKmh(metersPerSecond: Float): Int = (metersPerSecond * 3.6).roundToInt()

/**
 * Everything `DrivingModeActivity`'s UI needs to render one frame, from either
 * [DrivingNavigationMode]. Deliberately provider-agnostic: nothing here is a
 * Google Maps type, a MapLibre type, or a routing-engine type — only plain
 * values and the same [DrivingMediaState]/[DrivingNotification] models the
 * existing overlay already uses, so the two UIs can share data end to end.
 *
 * [speedLimitKmh] stays null unless a real speed-limit source is wired in —
 * never a guess (spec §10 "SPEED CARD").
 */
data class DrivingUiState(
    val navigationMode: DrivingNavigationMode = DrivingNavigationMode.EXTERNAL_MAPS_OVERLAY,
    val navigationActive: Boolean = false,
    val voiceState: DrivingVoiceState = DrivingVoiceState.READY,
    val nextManeuver: ManeuverUiModel? = null,
    val etaEpochMs: Long? = null,
    val remainingMinutes: Int? = null,
    val remainingDistanceMeters: Int? = null,
    val currentSpeedKmh: Int? = null,
    val speedLimitKmh: Int? = null,
    val media: DrivingMediaState? = null,
    val messages: List<DrivingNotification> = emptyList(),
    val incomingCall: Boolean = false,
    val expandedPanel: DrivingExpandedPanel = DrivingExpandedPanel.NONE,
    private val preCallExpandedPanel: DrivingExpandedPanel? = null,
) {
    /** "mostra X" / "riduci X" — a no-op while a call is up (spec §15 safe-zone). */
    fun togglePanel(panel: DrivingExpandedPanel): DrivingUiState =
        if (incomingCall) this
        else if (expandedPanel == panel) collapsePanel()
        else copy(expandedPanel = panel)

    fun collapsePanel(): DrivingUiState = copy(expandedPanel = DrivingExpandedPanel.NONE)

    /**
     * Entering a call remembers whatever was open so it can be restored, then
     * forces everything closed (spec §15: collapse MessageCard/MediaCard,
     * shrink VoiceDock — the card composables read [incomingCall] for that
     * last part). Leaving a call restores exactly what was open before it,
     * never something invented.
     */
    fun withIncomingCall(active: Boolean): DrivingUiState = when {
        active == incomingCall -> this
        active -> copy(incomingCall = true, preCallExpandedPanel = expandedPanel, expandedPanel = DrivingExpandedPanel.NONE)
        else -> copy(incomingCall = false, expandedPanel = preCallExpandedPanel ?: DrivingExpandedPanel.NONE, preCallExpandedPanel = null)
    }
}
