package com.simone.jarvismobile.core.driving.golden

import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.core.driving.DrivingMediaState
import com.simone.jarvismobile.core.driving.DrivingNotification
import com.simone.jarvismobile.core.driving.DrivingUiState
import com.simone.jarvismobile.core.driving.DrivingVoiceState
import com.simone.jarvismobile.core.driving.ManeuverUiModel
import com.simone.jarvismobile.core.navigation.ManeuverType

/**
 * A canned [DrivingUiState] reproducing every value the golden reference
 * itself shows (spec §7: "navigazione attiva, manovra, ETA, distanza,
 * limite velocità, velocità attuale, messaggi, musica, JARVIS listening,
 * route, vehicle marker" all visible at once) — so the whole screen can be
 * compared against `docs/design/jarvis_drive_reference.png` component by
 * component, not just whichever fields happen to be populated by a real
 * drive right now.
 *
 * Never used on the production data path: nothing in [com.simone.jarvismobile.driving.DrivingModeViewModel]
 * reads this unless a debug toggle explicitly asks for it, and that toggle
 * only exists behind `BuildConfig.DEBUG` in `app/`.
 */
object JarvisDriveMockState {
    val state = DrivingUiState(
        navigationActive = true,
        voiceState = DrivingVoiceState.LISTENING,
        nextManeuver = ManeuverUiModel(
            type = ManeuverType.TURN_LEFT,
            roadName = "Downtown Expy",
            distanceMeters = 1287, // ~0.8 mi
        ),
        etaEpochMs = null, // the reference shows a fixed "9:56" clock face, not a real relative time — left null, EtaBar shows "—" rather than a fabricated timestamp
        remainingMinutes = 15,
        remainingDistanceMeters = 11587, // ~7.2 mi
        currentSpeedKmh = 100, // ~62 mph
        speedLimitKmh = 105, // ~65 mph
        media = DrivingMediaState(
            title = "Time",
            artist = "Pink Floyd",
            playing = false,
            positionMs = null,
            durationMs = null,
            hasArt = false,
        ),
        messages = listOf(
            DrivingNotification(
                id = "mock-emma",
                app = "Messages",
                sender = "Emma",
                preview = "Be there in 10 mins",
                count = 1,
                postedAtEpochMs = 0L,
                supportsReply = true,
            ),
            DrivingNotification(
                id = "mock-alex",
                app = "Messages",
                sender = "Alex",
                preview = "Call you later",
                count = 1,
                postedAtEpochMs = 0L,
                supportsReply = true,
            ),
        ),
        expandedPanel = DrivingExpandedPanel.NONE,
    )
}
