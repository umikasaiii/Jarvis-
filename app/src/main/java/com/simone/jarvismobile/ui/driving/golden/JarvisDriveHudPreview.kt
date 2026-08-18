package com.simone.jarvismobile.ui.driving.golden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.ui.driving.DrivingMediaPanel
import com.simone.jarvismobile.ui.driving.DrivingSportColors
import com.simone.jarvismobile.ui.driving.EtaBar
import com.simone.jarvismobile.ui.driving.JarvisDriveDimensions
import com.simone.jarvismobile.ui.driving.JarvisDriveTopBar
import com.simone.jarvismobile.ui.driving.LayersButton
import com.simone.jarvismobile.ui.driving.ManeuverCard
import com.simone.jarvismobile.ui.driving.MessageCard
import com.simone.jarvismobile.ui.driving.SpeedCard
import com.simone.jarvismobile.ui.driving.VoiceDock

/**
 * Screenshot/preview infrastructure (spec §8): the HUD chrome only — top
 * bar, maneuver/speed row, ETA bar, messages/music row, voice dock — laid
 * out exactly like [com.simone.jarvismobile.ui.driving.DrivingModeScreen],
 * driven by [JarvisDriveMockState] instead of a live ViewModel, at a fixed
 * device size, so it can be compared frame-by-frame against
 * `docs/design/jarvis_drive_reference.png` in Android Studio's Preview pane.
 *
 * Deliberately NOT the full screen: [com.simone.jarvismobile.ui.navigation.JarvisMapView]
 * needs a real MapLibre GL surface, which Compose Previews don't provide —
 * a live map can't be part of a static preview regardless of tooling. This
 * covers everything that can be, i.e. every asset-backed and Compose-drawn
 * HUD element.
 *
 * This is a *duplicate* of `DrivingModeScreen`'s HUD-composing calls, not a
 * shared extraction, because that logic is threaded through
 * `DrivingModeViewModel` callbacks (`mediaArt()`, `searchDestinations()`,
 * ...) that only exist with a real Hilt graph — exactly the kind of
 * ViewModel dependency a Preview harness can't carry, so no-op lambdas
 * stand in here. Standard practice for Compose preview surfaces; if this
 * copy visibly diverges from the real screen's structure in a future edit,
 * that is itself a signal worth noticing, not a bug in the preview.
 *
 * No actual PNG comparison tooling exists here (no Robolectric/instrumented
 * test infrastructure in this repo, and this sandbox has no Android SDK to
 * run one) — the golden_reference.png vs actual_render.png diff loop spec
 * §8/§21 describes has to run from Android Studio or a device.
 */
@Composable
private fun JarvisDriveHudPreviewContent() {
    val state = JarvisDriveMockState.state
    Box(Modifier.fillMaxSize().background(DrivingSportColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            JarvisDriveTopBar(voiceState = state.voiceState, onOpenSettings = {})

            Row(
                Modifier.fillMaxWidth().padding(JarvisDriveDimensions.ScreenMargin),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.weight(1f)) {
                    state.nextManeuver?.let { ManeuverCard(it) }
                }
                SpeedCard(currentSpeedKmh = state.currentSpeedKmh, speedLimitKmh = state.speedLimitKmh)
            }

            Spacer(Modifier.height(4.dp))
            EtaBar(
                etaEpochMs = state.etaEpochMs,
                remainingMinutes = state.remainingMinutes,
                remainingDistanceMeters = state.remainingDistanceMeters,
                modifier = Modifier.padding(horizontal = JarvisDriveDimensions.ScreenMargin),
                onStop = {},
            )

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth().padding(JarvisDriveDimensions.ScreenMargin),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                MessageCard(
                    notifications = state.messages,
                    expanded = state.expandedPanel == DrivingExpandedPanel.MESSAGES,
                    onToggle = {},
                    onRead = {},
                    onReply = {},
                )
                if (state.media != null) {
                    DrivingMediaPanel(
                        media = state.media,
                        art = null,
                        queue = emptyList(),
                        expandedPanel = state.expandedPanel,
                        onToggle = {},
                        onPrevious = {},
                        onToggleTransport = {},
                        onNext = {},
                        modifier = Modifier.width(230.dp),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(JarvisDriveDimensions.ScreenMargin),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VoiceDock(
                    voiceState = state.voiceState,
                    compact = false,
                    onMicClick = {},
                    onPlacesClick = {},
                    modifier = Modifier.weight(1f),
                )
                LayersButton(onClick = {})
            }
        }
    }
}

/** iPhone-14-ish dp size, close to the golden reference's own 845:1620 aspect ratio. */
@Preview(name = "JARVIS Drive HUD vs golden reference", widthDp = 412, heightDp = 791, showBackground = true)
@Composable
private fun JarvisDriveHudPreview() {
    JarvisDriveHudPreviewContent()
}
