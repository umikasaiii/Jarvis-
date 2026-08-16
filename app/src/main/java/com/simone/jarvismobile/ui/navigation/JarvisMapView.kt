package com.simone.jarvismobile.ui.navigation

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.Route
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
import org.maplibre.android.geometry.LatLng as MapLibreLatLng

private const val ROUTE_SOURCE = "jarvis-route-src"
private const val ROUTE_LAYER = "jarvis-route-layer"

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
 */
@Composable
fun JarvisMapView(
    cameraTarget: LatLng?,
    route: Route?,
    stylePmtilesPath: String?,
    followCamera: Boolean,
    modifier: Modifier = Modifier,
    styleAsset: String = "jarvis-navigation.json",
    onLongPress: (LatLng) -> Unit = {},
) {
    val context = LocalContext.current
    var styleReady by remember { mutableStateOf(false) }

    // Initialise MapLibre once, then build the MapView. Both are cheap to hold.
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

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
        m.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            if (style.getSource(ROUTE_SOURCE) == null) {
                style.addSource(GeoJsonSource(ROUTE_SOURCE))
                style.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        PropertyFactory.lineColor(AndroidColor.parseColor("#4FD1E0")),
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

    // Follow the vehicle: recentre the camera on each fix while following.
    LaunchedEffect(cameraTarget, map, followCamera) {
        val m = map ?: return@LaunchedEffect
        val target = cameraTarget ?: return@LaunchedEffect
        if (followCamera) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(MapLibreLatLng(target.lat, target.lon), 16.0))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}
