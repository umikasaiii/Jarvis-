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
import androidx.compose.material.icons.filled.Close
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
import com.simone.jarvismobile.core.navigation.ManeuverType
import com.simone.jarvismobile.core.navigation.NavigationProgress
import com.simone.jarvismobile.core.navigation.LatLng as CoreLatLng
import com.simone.jarvismobile.navigation.GpsStatus
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale

private const val ROUTE_SOURCE = "jarvis-route-src"
private const val ROUTE_LAYER = "jarvis-route-layer"

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
    val route by viewModel.route.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    var styleReady by remember { mutableStateOf(false) }

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
            // Long-press sets a destination and starts an offline route to it.
            m.addOnMapLongClickListener { p ->
                viewModel.navigateTo(CoreLatLng(p.latitude, p.longitude))
                true
            }
            map = m
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Load the style once the map is ready, injecting the covering region's local
    // PMTiles as the vector source. With no region installed the style still
    // renders (dark background) and the "download map" banner shows.
    LaunchedEffect(map, covering) {
        val m = map ?: return@LaunchedEffect
        val base = runCatching {
            context.assets.open("jarvis-navigation.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@LaunchedEffect
        val pmtilesPath = viewModel.coveringPmtilesPath()
        val styleJson = if (pmtilesPath != null) {
            base.replace("pmtiles://LOCAL_REGION_PLACEHOLDER", "pmtiles://file://$pmtilesPath")
        } else {
            base
        }
        styleReady = false
        m.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            if (style.getSource(ROUTE_SOURCE) == null) {
                style.addSource(GeoJsonSource(ROUTE_SOURCE))
                style.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        PropertyFactory.lineColor(android.graphics.Color.parseColor("#4FD1E0")),
                        PropertyFactory.lineWidth(7f),
                    ),
                )
            }
            styleReady = true
        }
    }

    // Draw / clear the computed route as a line on the map.
    LaunchedEffect(route, styleReady, map) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val src = m.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return@LaunchedEffect
        val r = route
        if (r == null) {
            src.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        } else {
            src.setGeoJson(LineString.fromLngLats(r.geometry.map { Point.fromLngLat(it.lon, it.lat) }))
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
        // Top maneuver panel while navigating; otherwise just the back bar.
        if (route != null) {
            ManeuverPanel(progress = progress, modifier = Modifier.align(Alignment.TopCenter))
        } else if (fix != null && covering != null) {
            HintPanel(modifier = Modifier.align(Alignment.TopCenter))
        }
        BottomHud(
            fix = fix,
            gpsStatus = gpsStatus,
            covered = covering != null,
            progress = if (route != null) progress else null,
            onStop = { viewModel.stopNavigation() },
            navigating = route != null,
        )

        // Controls column (recenter).
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
private fun BottomHud(
    fix: GpsFix?,
    gpsStatus: GpsStatus,
    covered: Boolean,
    progress: NavigationProgress?,
    navigating: Boolean,
    onStop: () -> Unit,
) {
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
                    Text(
                        progress?.let { formatDuration(it.etaSeconds) } ?: "—",
                        color = Silver, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Column {
                    Text("Distanza", color = Muted, fontSize = 11.sp)
                    Text(
                        progress?.let { formatDistance(it.remainingDistanceMeters) } ?: "—",
                        color = Silver, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (navigating) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Red),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Ferma", tint = Silver)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("GPS", color = Muted, fontSize = 11.sp)
                        Text(
                            gpsLabel(gpsStatus, fix, covered),
                            color = if (gpsStatus == GpsStatus.WEAK) Red else Silver,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManeuverPanel(progress: NavigationProgress?, modifier: Modifier) {
    val maneuver = progress?.nextManeuver
    Column(modifier.fillMaxWidth().padding(top = 64.dp, start = 12.dp, end = 12.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(maneuverGlyph(maneuver?.type), fontSize = 30.sp)
                androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp))
                Column {
                    Text(
                        progress?.let { formatDistance(it.distanceToManeuverMeters) } ?: "—",
                        color = Silver, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                    if (maneuver?.roadName?.isNotBlank() == true) {
                        Text(maneuver.roadName, color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HintPanel(modifier: Modifier) {
    Box(
        modifier.padding(top = 64.dp, start = 12.dp, end = 12.dp)
            .clip(RoundedCornerShape(14.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text("Tieni premuto sulla mappa per impostare la destinazione.", color = Muted, fontSize = 13.sp)
    }
}

private fun maneuverGlyph(type: ManeuverType?): String = when (type) {
    ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT, ManeuverType.KEEP_LEFT -> "↰"
    ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.KEEP_RIGHT -> "↱"
    ManeuverType.UTURN -> "⤺"
    ManeuverType.ROUNDABOUT -> "⟳"
    ManeuverType.ARRIVE -> "◎"
    else -> "↑"
}

private fun formatDistance(meters: Double): String = when {
    meters >= 1000 -> String.format(Locale.ITALY, "%.1f km", meters / 1000.0)
    else -> "${(meters / 10).toInt() * 10} m"
}

private fun formatDuration(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return when {
        m >= 60 -> "${m / 60} h ${m % 60} min"
        m >= 1 -> "$m min"
        else -> "<1 min"
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
