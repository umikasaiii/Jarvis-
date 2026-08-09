package com.simone.jarvismobile.navigation

import com.simone.jarvismobile.core.navigation.AStarRouter
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.core.navigation.RoadGraph
import com.simone.jarvismobile.core.navigation.RouteOptions
import com.simone.jarvismobile.core.navigation.RoutingError
import com.simone.jarvismobile.core.navigation.RoutingProfile
import com.simone.jarvismobile.core.navigation.RoutingResult
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline routing service. It loads a region's road graph once (cached) and
 * runs the deterministic `:core` A* over it — no Directions API, no dependency on
 * the AI model (spec §6). When a region has no routing data yet, calculation
 * fails with [RoutingError.NO_GRAPH] and the UI says so instead of faking a route.
 *
 * The BRouter/`.rd5` production path converts into the same [RoadGraph] the router
 * already consumes; this engine is agnostic to how the graph was produced.
 */
@Singleton
class OfflineRoutingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: InstalledRegionStore,
) {
    @Volatile private var cachedRegionId: String? = null
    @Volatile private var cachedGraph: RoadGraph? = null
    @Volatile private var cachedMtime: Long = 0L

    suspend fun hasRoutingData(region: RegionMetadata): Boolean =
        graphFor(region) != null

    /** Deterministic route from [start] to [destination] within [region]. */
    suspend fun calculateRoute(
        region: RegionMetadata?,
        start: LatLng,
        destination: LatLng,
        profile: RoutingProfile = RoutingProfile.CAR,
        options: RouteOptions = RouteOptions(),
        waypoints: List<LatLng> = emptyList(),
    ): RoutingResult = withContext(Dispatchers.Default) {
        val graph = region?.let { graphFor(it) }
            ?: return@withContext RoutingResult.Failure(RoutingError.NO_GRAPH)
        AStarRouter(graph).route(start, destination, profile, options, waypoints)
    }

    suspend fun recalculateRoute(
        region: RegionMetadata?,
        current: LatLng,
        destination: LatLng,
        profile: RoutingProfile = RoutingProfile.CAR,
        options: RouteOptions = RouteOptions(),
    ): RoutingResult = calculateRoute(region, current, destination, profile, options)

    private suspend fun graphFor(region: RegionMetadata): RoadGraph? = withContext(Dispatchers.IO) {
        val file = java.io.File(store.regionDir(region.id), RoadGraphLoader.ROUTING_FILE)
        val mtime = if (file.exists()) file.lastModified() else 0L
        // Reuse the cache only if the routing file hasn't changed (a fresh download
        // bumps its mtime, so newly-added routing data takes effect immediately).
        cachedGraph?.let { if (cachedRegionId == region.id && cachedMtime == mtime) return@withContext it }
        val graph = RoadGraphLoader.load(store.regionDir(region.id))
        if (graph != null) {
            cachedGraph = graph
            cachedRegionId = region.id
            cachedMtime = mtime
        }
        graph
    }
}
