package com.simone.jarvismobile.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MapLibreLatLng

// The source and its three route-glow/route-outline/route-main layers are
// declared directly in the style JSON (jarvis-navigation.json) — this view
// only ever feeds it new geometry, never creates sources/layers itself.
private const val ROUTE_SOURCE = "jarvis-route-src"

/**
 * The one Compose component in JARVIS allowed to know about MapLibre
 * (Driving Mode V2 spec §5) — every caller talks only in `:core` types
 * ([LatLng], [Route]), never a MapLibre type. Renders the JARVIS dark style
 * (a local asset, [styleAsset]) with the covering region's local PMTiles
 * injected when [stylePmtilesPath] is known, the active [route] drawn as a
 * line, and the camera recentred on [cameraTarget] while [followCamera].
 *
 * Extracted unchanged from the original inline MapLibre setup in
 * `NavigationScreen` so the offline-navigation screen and the future
 * `DrivingModeActivity` share the exact same map rendering path instead of
 * two copies of it (spec §17 "non duplicare").
 *
 * [cameraBearingDegrees]/[cameraTiltDegrees] are optional (spec §8
 * `DrivingCameraController`): left null, the camera stays the simple
 * north-up top-down view the offline Navigation screen has always used —
 * only a caller that opts in (JARVIS Drive's follow mode) gets heading-up
 * rotation and a navigation tilt.
 *
 * [onUserGesture] fires only for a real touch pan/zoom
 * ([org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE]),
 * never for this view's own follow-camera [org.maplibre.android.maps.MapLibreMap.moveCamera]
 * calls — so a caller can drop out of follow mode on a real drag without
 * fighting its own programmatic recentring (spec §12 FREE mode).
 *
 * [onVehicleScreenPosition] reports where [cameraTarget] actually lands on
 * screen right now (`Projection.toScreenLocation`, in this view's own pixel
 * space) — recomputed whenever the camera settles or the target moves, so a
 * caller can draw a puck at the vehicle's real position even while panned
 * away in FREE mode, instead of assuming it's always screen-centre (only
 * true while following). Null once there is no target to project.
 */
@Composable
fun JarvisMapView(
    cameraTarget: LatLng?,
    route: Route?,
    stylePmtilesPath: String?,
    followCamera: Boolean,
    modifier: Modifier = Modifier,
    styleAsset: String = "jarvis-navigation.json",
    cameraZoom: Double = 16.0,
    cameraBearingDegrees: Float? = null,
    cameraTiltDegrees: Float? = null,
    onLongPress: (LatLng) -> Unit = {},
    onUserGesture: () -> Unit = {},
    onVehicleScreenPosition: (Offset?) -> Unit = {},
) {
    val context = LocalContext.current
    var styleReady by remember { mutableStateOf(false) }
    val latestCameraTarget by rememberUpdatedState(cameraTarget)
    val latestOnVehicleScreenPosition by rememberUpdatedState(onVehicleScreenPosition)

    // Initialise MapLibre once, then build the MapView. Both are cheap to hold.
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    fun projectVehicle(m: MapLibreMap) {
        val target = latestCameraTarget
        if (target == null) {
            latestOnVehicleScreenPosition(null)
            return
        }
        val screen = m.projection.toScreenLocation(MapLibreLatLng(target.lat, target.lon))
        latestOnVehicleScreenPosition(Offset(screen.x, screen.y))
    }

    // Drive the MapView lifecycle from composition (the host is a full-screen surface).
    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = true
            m.addOnMapLongClickListener { p ->
                onLongPress(LatLng(p.latitude, p.longitude))
                true
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture()
            }
            // Re-project the vehicle's screen position every time the camera
            // settles (pan, zoom, our own follow moves alike) — the only
            // reliable way to know where cameraTarget actually landed.
            m.addOnCameraIdleListener { projectVehicle(m) }
            map = m
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Also re-project immediately on a fresh fix, without waiting for the
    // camera to move at all (e.g. while stationary in FREE mode).
    LaunchedEffect(cameraTarget, map) {
        map?.let { projectVehicle(it) }
    }

    // Load the style once the map is ready, injecting the covering region's local
    // PMTiles as the vector source. With no region installed the style still
    // renders (dark background) and the caller's own "download map" state can show.
    LaunchedEffect(map, stylePmtilesPath, styleAsset) {
        val m = map ?: return@LaunchedEffect
        val base = runCatching {
            context.assets.open(styleAsset).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@LaunchedEffect
        val styleJson = if (stylePmtilesPath != null) {
            base.replace("pmtiles://LOCAL_REGION_PLACEHOLDER", "pmtiles://file://$stylePmtilesPath")
        } else {
            base
        }
        styleReady = false
        m.setStyle(Style.Builder().fromJson(styleJson)) { styleReady = true }
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

    // Follow the vehicle: recentre (and, when asked, rotate/tilt) the camera
    // on each fix while following.
    LaunchedEffect(cameraTarget, map, followCamera, cameraZoom, cameraBearingDegrees, cameraTiltDegrees) {
        val m = map ?: return@LaunchedEffect
        val target = cameraTarget ?: return@LaunchedEffect
        if (!followCamera) return@LaunchedEffect
        val position = CameraPosition.Builder()
            .target(MapLibreLatLng(target.lat, target.lon))
            .zoom(cameraZoom)
            .bearing((cameraBearingDegrees ?: 0f).toDouble())
            .tilt((cameraTiltDegrees ?: 0f).toDouble())
            .build()
        m.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}
