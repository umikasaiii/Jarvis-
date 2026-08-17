package com.simone.jarvismobile.navigation

import android.util.Log
import com.simone.jarvismobile.core.navigation.Geo
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.Maneuver
import com.simone.jarvismobile.core.navigation.ManeuverType
import com.simone.jarvismobile.core.navigation.Route
import com.simone.jarvismobile.core.navigation.RouteSegment
import com.simone.jarvismobile.core.navigation.RoutingProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Online routing fallback via TomTom's Routing API — the same account/key
 * already saved for live traffic and destination search. [NavigationRepository]
 * calls this only after the offline [AStarRouterEngine] fails (no routing
 * data for the area — the far more common case now that JARVIS Drive's map
 * itself can render without any offline region installed), and only while
 * "Traffico live (TomTom)" is on. A fourth fill-in on the same account, same
 * shape as [TomTomSearchFetcher]: this sends the start/destination
 * coordinates to TomTom, never the vault/transcript/memory
 * (`docs/PRIVACY.md`).
 *
 * Endpoint (`.../routing/1/calculateRoute/{lat},{lon}:{lat},{lon}/json`) is
 * confirmed against TomTom's own `tomtom-international/postman-collections`
 * GitHub repo. The response shape (`routes[].summary`,
 * `.legs[].points[]`, `.guidance.instructions[]`) and the maneuver-string
 * mapping in [mapManeuver] are based on TomTom's long-stable, widely
 * documented Routing API, not a payload actually inspected from this
 * sandbox (its network proxy blocks TomTom's docs sites) — an unrecognized
 * maneuver string safely falls back to [ManeuverType.CONTINUE] rather than
 * guessing wrong. Needs on-device confirmation that turn instructions read
 * out correctly.
 */
@Singleton
class TomTomRoutingEngine @Inject constructor(
    private val keyStore: TrafficApiKeyStore,
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Null when no key is saved, the request fails, or the response is unusable. */
    suspend fun calculateRoute(start: LatLng, destination: LatLng, profile: RoutingProfile): Route? =
        withContext(Dispatchers.IO) {
            val key = keyStore.apiKey ?: return@withContext null
            runCatching {
                val travelMode = when (profile) {
                    RoutingProfile.CAR -> "car"
                    RoutingProfile.MOTORCYCLE -> "motorcycle"
                    RoutingProfile.BICYCLE -> "bicycle"
                    RoutingProfile.WALKING -> "pedestrian"
                }
                val locations = "${start.lat},${start.lon}:${destination.lat},${destination.lon}"
                val url = "https://api.tomtom.com/routing/1/calculateRoute/$locations/json" +
                    "?key=$key&travelMode=$travelMode&instructionsType=text&language=it-IT"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    parseRoute(JSONObject(body), destination, profile)
                }
            }.onFailure { Log.w(TAG, "traffic_route_failed ${it.javaClass.simpleName}") }.getOrNull()
        }

    private fun parseRoute(body: JSONObject, destination: LatLng, profile: RoutingProfile): Route? {
        val route = body.optJSONArray("routes")?.optJSONObject(0) ?: return null
        val legs = route.optJSONArray("legs") ?: return null
        val geometry = ArrayList<LatLng>()
        for (i in 0 until legs.length()) {
            val points = legs.getJSONObject(i).optJSONArray("points") ?: continue
            for (j in 0 until points.length()) {
                val p = points.getJSONObject(j)
                geometry += LatLng(p.getDouble("latitude"), p.getDouble("longitude"))
            }
        }
        if (geometry.size < 2) return null

        val summary = route.optJSONObject("summary")
        val distanceMeters = summary?.optDouble("lengthInMeters")?.takeIf { !it.isNaN() && it > 0 }
            ?: Geo.polylineLength(geometry)
        val durationSeconds = summary?.optDouble("travelTimeInSeconds")?.takeIf { !it.isNaN() && it > 0 }
            ?: (distanceMeters / 13.9)

        val maneuvers = ArrayList<Maneuver>()
        route.optJSONObject("guidance")?.optJSONArray("instructions")?.let { instructions ->
            for (i in 0 until instructions.length()) {
                val instr = instructions.getJSONObject(i)
                val point = instr.optJSONObject("point") ?: continue
                val at = LatLng(point.getDouble("latitude"), point.getDouble("longitude"))
                maneuvers += Maneuver(
                    type = mapManeuver(instr.optString("maneuver")),
                    at = at,
                    geometryIndex = nearestGeometryIndex(geometry, at),
                    roadName = instr.optString("street", ""),
                )
            }
        }
        if (maneuvers.firstOrNull()?.type != ManeuverType.DEPART) {
            maneuvers.add(0, Maneuver(ManeuverType.DEPART, geometry.first(), 0))
        }
        if (maneuvers.lastOrNull()?.type != ManeuverType.ARRIVE) {
            maneuvers += Maneuver(ManeuverType.ARRIVE, destination, geometry.lastIndex)
        }

        val avgSpeedMps = (distanceMeters / durationSeconds).takeIf { it > 0 } ?: 13.9
        val anchors = (listOf(0) + maneuvers.map { it.geometryIndex } + listOf(geometry.lastIndex))
            .distinct().sorted()
        val segments = ArrayList<RouteSegment>()
        for (i in 1 until anchors.size) {
            val a = anchors[i - 1]
            val b = anchors[i]
            segments += RouteSegment(
                a, b,
                distanceMeters = Geo.polylineLength(geometry.subList(a, b + 1)),
                nominalSpeedMps = avgSpeedMps,
            )
        }

        return Route(geometry, distanceMeters, durationSeconds, maneuvers, segments, destination, profile)
    }

    private fun nearestGeometryIndex(geometry: List<LatLng>, target: LatLng): Int {
        var bestIndex = 0
        var bestDistSq = Double.MAX_VALUE
        geometry.forEachIndexed { i, p ->
            val dLat = p.lat - target.lat
            val dLon = p.lon - target.lon
            val distSq = dLat * dLat + dLon * dLon
            if (distSq < bestDistSq) { bestDistSq = distSq; bestIndex = i }
        }
        return bestIndex
    }

    private fun mapManeuver(raw: String): ManeuverType = when (raw.uppercase()) {
        "DEPART" -> ManeuverType.DEPART
        "ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT", "WAYPOINT_REACHED" -> ManeuverType.ARRIVE
        "STRAIGHT", "FOLLOW", "SWITCH_MAIN_ROAD", "SWITCH_PARALLEL_ROAD" -> ManeuverType.CONTINUE
        "TURN_LEFT", "WAYPOINT_LEFT" -> ManeuverType.TURN_LEFT
        "TURN_RIGHT", "WAYPOINT_RIGHT" -> ManeuverType.TURN_RIGHT
        "SHARP_LEFT" -> ManeuverType.SHARP_LEFT
        "SHARP_RIGHT" -> ManeuverType.SHARP_RIGHT
        "BEAR_LEFT" -> ManeuverType.SLIGHT_LEFT
        "BEAR_RIGHT" -> ManeuverType.SLIGHT_RIGHT
        "KEEP_LEFT", "MOTORWAY_EXIT_LEFT" -> ManeuverType.KEEP_LEFT
        "KEEP_RIGHT", "MOTORWAY_EXIT_RIGHT", "TAKE_EXIT" -> ManeuverType.KEEP_RIGHT
        "MAKE_UTURN", "TRY_MAKE_UTURN" -> ManeuverType.UTURN
        "ROUNDABOUT_CROSS", "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT", "ROUNDABOUT_BACK" -> ManeuverType.ROUNDABOUT
        else -> ManeuverType.CONTINUE
    }

    private companion object {
        const val TAG = "JarvisTraffic"
    }
}
