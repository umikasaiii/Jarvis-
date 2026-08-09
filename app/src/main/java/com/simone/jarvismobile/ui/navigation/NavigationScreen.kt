package com.simone.jarvismobile.ui.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationSearching
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.navigation.GpsFix
import com.simone.jarvismobile.navigation.GpsStatus
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private val Ink = Color(0xFF0A0E14)
private val Panel = Color(0xE6121A26)
private val Silver = Color(0xFFD9DEE3)
private val Red = Color(0xFFE23A2E)
private val Muted = Color(0xFF8A97A6)

/**
 * The offline navigation screen: a native MapLibre map with the JARVIS dark
 * style, the live GNSS position (centred, heading-up style), and the top/bottom
 * HUD. When no offline region covers the current position it shows the
 * "download the map first" state instead of any online fallback (spec §16).
 *
 * Routing/turn-by-turn overlays are wired to the pure engine and fill in with the
 * BRouter + search stages; this stage delivers real map rendering + real GNSS.
 */
@Composable
fun NavigationScreen(
    onBack: () -> Unit,
    onOpenMaps: () -> Unit = {},
    viewModel: NavigationViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val gpsStatus by viewModel.gpsStatus.collectAsStateWithLifecycle()
    val covering by viewModel.coveringRegion.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (ok) viewModel.start()
    }

    // Initialise MapLibre once, then build the MapView. Both are cheap to hold.
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var following by remember { mutableStateOf(true) }

    // Drive the MapView lifecycle from composition (screen is a full overlay).
    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = true
            m.setStyle(Style.Builder().fromUri("asset://jarvis-navigation.json")) { }
            map = m
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Start/stop GNSS with the screen; ask for permission if needed.
    LaunchedEffect(Unit) {
        if (granted) viewModel.start() else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    // Follow the vehicle: recentre the camera on each fix while following.
    LaunchedEffect(fix, map, following) {
        val m = map ?: return@LaunchedEffect
        val f = fix ?: return@LaunchedEffect
        if (following) {
            m.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    org.maplibre.android.geometry.LatLng(f.location.lat, f.location.lon),
                    16.0,
                ),
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // The vehicle marker: the camera keeps the position centred, so a centre
        // dot represents "you" in the heading-up view.
        if (fix != null) {
            Box(
                Modifier.align(Alignment.Center).size(18.dp).clip(CircleShape)
                    .background(Red),
            )
        }

        TopBar(onBack = onBack)
        BottomHud(fix = fix, gpsStatus = gpsStatus, covered = covering != null)

        // Controls column (recenter). Mute/overview/stop live in the HUD.
        IconButton(
            onClick = { following = true },
            modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp)
                .size(48.dp).clip(CircleShape).background(Panel),
        ) {
            Icon(Icons.Filled.LocationSearching, contentDescription = "Ricentra", tint = Silver)
        }

        // Offline-gap state: this area isn't downloaded (spec §16).
        if (fix != null && covering == null) {
            DownloadMapBanner(onOpenMaps = onOpenMaps, modifier = Modifier.align(Alignment.Center))
        }
        if (!granted) {
            PermissionNotice(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(Panel),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Silver)
        }
    }
}

@Composable
private fun BottomHud(fix: GpsFix?, gpsStatus: GpsStatus, covered: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Box(
            Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("ETA", color = Muted, fontSize = 11.sp)
                    Text("—", color = Silver, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Column {
                    Text("Distanza", color = Muted, fontSize = 11.sp)
                    Text("—", color = Silver, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("GPS", color = Muted, fontSize = 11.sp)
                    Text(
                        gpsLabel(gpsStatus, fix, covered),
                        color = if (gpsStatus == GpsStatus.WEAK) Red else Silver,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private fun gpsLabel(status: GpsStatus, fix: GpsFix?, covered: Boolean): String = when {
    status == GpsStatus.NONE -> "in attesa"
    status == GpsStatus.ACQUIRING || fix == null -> "acquisizione…"
    status == GpsStatus.WEAK -> "debole"
    !covered -> "no mappa"
    else -> "ok"
}

@Composable
private fun DownloadMapBanner(onOpenMaps: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(24.dp).clip(RoundedCornerShape(18.dp)).background(Panel).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Zona non disponibile offline", color = Silver, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Scarica la mappa di questa regione prima di partire.",
            color = Muted, fontSize = 13.sp,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        androidx.compose.material3.OutlinedButton(onClick = onOpenMaps) { Text("Mappe offline") }
    }
}

@Composable
private fun PermissionNotice(modifier: Modifier) {
    Column(
        modifier.padding(24.dp).clip(RoundedCornerShape(18.dp)).background(Panel).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Serve il permesso di posizione", color = Silver, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Per mostrare la tua posizione sulla mappa.", color = Muted, fontSize = 13.sp)
    }
}
