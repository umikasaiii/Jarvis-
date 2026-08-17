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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.driving.DrivingCameraController
import com.simone.jarvismobile.core.driving.DrivingCameraMode
import com.simone.jarvismobile.core.driving.DrivingCameraUiState
import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.driving.DrivingMapsLauncher
import com.simone.jarvismobile.driving.DrivingModeViewModel
import com.simone.jarvismobile.ui.navigation.JarvisMapView
import kotlin.math.roundToInt

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
    val context = LocalContext.current
    val density = LocalDensity.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val coveringRegion by viewModel.coveringRegion.collectAsStateWithLifecycle()
    val noOfflineMap = fix != null && coveringRegion == null

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

    // Heading-up follow: the map itself rotates to match travel direction
    // (spec §8 DrivingCameraController), so the puck stays fixed pointing up
    // rather than also rotating — rotating both would double-count bearing.
    val cameraState = DrivingCameraController.forFollow(fix)
    var cameraUi by remember { mutableStateOf(DrivingCameraUiState()) }
    val following = cameraUi.mode == DrivingCameraMode.FOLLOW
    var vehicleScreenPosition by remember { mutableStateOf<Offset?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    // GPS speed is noisy near zero even while parked; a couple of m/s of slack
    // keeps that jitter from flipping the search UI to "moving" while stopped.
    val stationary = (fix?.speedMps ?: 0f) < 2f

    Box(Modifier.fillMaxSize().background(DrivingSportColors.Bg)) {
        JarvisMapView(
            cameraTarget = fix?.location,
            route = route,
            stylePmtilesPath = viewModel.coveringPmtilesPath(),
            followCamera = following,
            cameraZoom = cameraState?.zoom ?: 16.0,
            cameraBearingDegrees = if (following) cameraState?.bearingDegrees else null,
            cameraTiltDegrees = if (following) cameraState?.tiltDegrees else null,
            onLongPress = { viewModel.navigateTo(it) },
            onUserGesture = { cameraUi = cameraUi.userPanned() },
            onVehicleScreenPosition = { vehicleScreenPosition = it },
            modifier = Modifier.fillMaxSize(),
        )

        if (fix != null) {
            if (following) {
                // Always true while following: the camera itself keeps the
                // target centred, so this is cheaper than reading the
                // projected offset every frame for the common case.
                VehiclePuck(bearingDegrees = null, modifier = Modifier.align(Alignment.Center))
            } else {
                // FREE mode: the map may be panned anywhere, so the puck is
                // drawn at its real projected screen position (spec §10) —
                // never assumed to be centre — and shows its true bearing
                // since the map itself is no longer heading-up rotated here.
                vehicleScreenPosition?.let { screen ->
                    val puckOuterPx = with(density) { (JarvisDriveDimensions.PuckSize + 14.dp).toPx() }
                    VehiclePuck(
                        bearingDegrees = fix?.bearingDegrees,
                        modifier = Modifier.offset {
                            IntOffset(
                                (screen.x - puckOuterPx / 2f).roundToInt(),
                                (screen.y - puckOuterPx / 2f).roundToInt(),
                            )
                        },
                    )
                }
            }
        }

        // RECENTER (spec §12): only shown once a real drag has left follow mode.
        if (cameraUi.mode == DrivingCameraMode.FREE) {
            IconButton(
                onClick = { cameraUi = cameraUi.recenter() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(JarvisDriveDimensions.ScreenMargin)
                    .clip(CircleShape)
                    .background(DrivingSportColors.PanelStrong),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Centra sul veicolo", tint = DrivingSportColors.Accent)
            }
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
                onMicClick = viewModel::startVoiceSession,
                onPlacesClick = { showSearch = true },
                modifier = Modifier.padding(JarvisDriveDimensions.ScreenMargin),
            )
        }

        if (showSearch) {
            DestinationSearchSheet(
                stationary = stationary,
                onSearch = viewModel::searchDestinations,
                onSelect = viewModel::navigateToPlace,
                onClose = { showSearch = false },
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

        // No installed region covers where we are (spec §15): never crash, never
        // silently show an empty map with no explanation — offer the same three
        // ways out the offline Navigation screen already gives (minus the map
        // preview, which JARVIS Drive doesn't have room for behind this banner).
        if (noOfflineMap && granted) {
            Box(
                Modifier.align(Alignment.Center).padding(24.dp)
                    .clip(RoundedCornerShape(18.dp)).background(DrivingSportColors.PanelStrong).padding(20.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Nessuna mappa offline installata",
                        color = DrivingSportColors.TextMain,
                        style = JarvisDriveTypography.CardValueMedium,
                    )
                    Text(
                        "Scarica la mappa di questa zona per usare JARVIS Drive qui.",
                        color = DrivingSportColors.Muted,
                        style = JarvisDriveTypography.Body,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { viewModel.requestImportMap(); onClose() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Importa una mappa") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { DrivingMapsLauncher.openMaps(context); onClose() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Usa Google Maps") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Torna indietro") }
                }
            }
        }
    }
}
