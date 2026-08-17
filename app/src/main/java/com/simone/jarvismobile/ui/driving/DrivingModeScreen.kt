package com.simone.jarvismobile.ui.driving

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.driving.DrivingModeViewModel
import com.simone.jarvismobile.ui.navigation.JarvisMapView

/**
 * `INTERNAL_JARVIS_NAVIGATION`'s screen (spec §2/§10): a real, in-app,
 * edge-to-edge Compose Activity content — never a `WindowManager` overlay.
 * Every card is provider-agnostic ([com.simone.jarvismobile.core.driving.DrivingUiState]);
 * [JarvisMapView] is the only thing here that knows about MapLibre, and
 * [DrivingMediaPanel]/[MessageCard]'s inner [NotifSheet] are the exact same
 * composables the existing Google-Maps overlay already renders.
 */
@Composable
fun DrivingModeScreen(
    onClose: () -> Unit,
    viewModel: DrivingModeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (ok) viewModel.startLocation()
    }
    LaunchedEffect(Unit) {
        if (granted) viewModel.startLocation() else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopLocation() } }

    Box(Modifier.fillMaxSize().background(DrivingSportColors.Bg)) {
        JarvisMapView(
            cameraTarget = fix?.location,
            route = route,
            stylePmtilesPath = viewModel.coveringPmtilesPath(),
            followCamera = true,
            onLongPress = { viewModel.navigateTo(it) },
            modifier = Modifier.fillMaxSize(),
        )

        if (fix != null) {
            VehiclePuck(bearingDegrees = fix?.bearingDegrees, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.fillMaxSize()) {
            JarvisDriveTopBar(voiceState = state.voiceState, onOpenSettings = onClose)

            Row(
                Modifier.fillMaxWidth().padding(JarvisDriveDimensions.ScreenMargin),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.weight(1f)) {
                    state.nextManeuver?.let { ManeuverCard(it) }
                }
                SpeedCard(currentSpeedKmh = state.currentSpeedKmh, speedLimitKmh = state.speedLimitKmh)
            }

            if (state.navigationActive) {
                Spacer(Modifier.height(4.dp))
                EtaBar(
                    etaEpochMs = state.etaEpochMs,
                    remainingMinutes = state.remainingMinutes,
                    remainingDistanceMeters = state.remainingDistanceMeters,
                    modifier = Modifier.padding(horizontal = JarvisDriveDimensions.ScreenMargin),
                )
            }

            Spacer(Modifier.weight(1f))

            if (!state.incomingCall) {
                Row(
                    Modifier.fillMaxWidth().padding(JarvisDriveDimensions.ScreenMargin),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    MessageCard(
                        notifications = state.messages,
                        expanded = state.expandedPanel == DrivingExpandedPanel.MESSAGES,
                        onToggle = { viewModel.togglePanel(DrivingExpandedPanel.MESSAGES) },
                        onRead = viewModel::readNotification,
                        onReply = viewModel::promptReply,
                    )
                    if (state.media != null) {
                        DrivingMediaPanel(
                            media = state.media,
                            art = viewModel.mediaArt(),
                            queue = viewModel.mediaQueue(),
                            expandedPanel = state.expandedPanel,
                            onToggle = { viewModel.togglePanel(DrivingExpandedPanel.MEDIA) },
                            onPrevious = viewModel::mediaPrevious,
                            onToggleTransport = viewModel::mediaToggleTransport,
                            onNext = viewModel::mediaNext,
                            modifier = Modifier.width(230.dp),
                        )
                    }
                }
            }

            VoiceDock(
                voiceState = state.voiceState,
                compact = state.incomingCall,
                onMicClick = { /* SessionCoordinator session start is already reachable via wake word / Home; a dedicated mic-press entry point is a future integration point (spec §14). */ },
                modifier = Modifier.padding(JarvisDriveDimensions.ScreenMargin),
            )
        }

        if (!granted) {
            Box(
                Modifier.align(Alignment.Center).padding(24.dp)
                    .clip(RoundedCornerShape(18.dp)).background(DrivingSportColors.PanelStrong).padding(20.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Serve il permesso di posizione", color = DrivingSportColors.TextMain, style = JarvisDriveTypography.CardValueMedium)
                    Text("Per mostrare la tua posizione sulla mappa.", color = DrivingSportColors.Muted, style = JarvisDriveTypography.Body)
                }
            }
        }
    }
}
