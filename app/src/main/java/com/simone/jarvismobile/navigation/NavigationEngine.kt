package com.simone.jarvismobile.navigation

import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.core.navigation.RouteOptions
import com.simone.jarvismobile.core.navigation.RoutingProfile
import com.simone.jarvismobile.core.navigation.RoutingResult

/**
 * The one seam between [NavigationRepository] and whatever actually computes a
 * route (Driving Mode real-navigation-engine spec §2). Every method takes a
 * plain [RegionMetadata] and returns the same `:core` [RoutingResult] regardless
 * of backend, so [NavigationRepository], the route-progress/off-route/rerouting
 * logic, the UI and MapLibre never know or care which engine produced a route.
 *
 * Today the only implementation is [AStarRouterEngine] — the existing, already
 * offline, already-tested pure-Kotlin `:core` A* router — bound in
 * [com.simone.jarvismobile.di.NavigationEngineModule]. A future native routing
 * backend (see `ValhallaNavigationEngine.kt` — deliberately not implemented,
 * its real API was not verified enough to guess at) slots in behind this same
 * contract with a single `@Binds` change; nothing above this interface needs
 * to change at all.
 */
interface NavigationEngine {

    /** True once [region] actually has data this engine can route on. */
    suspend fun hasRoutingData(region: RegionMetadata): Boolean

    /** A fresh route from [start] to [destination] (optionally via [waypoints]). */
    suspend fun calculateRoute(
        region: RegionMetadata?,
        start: LatLng,
        destination: LatLng,
        profile: RoutingProfile = RoutingProfile.CAR,
        options: RouteOptions = RouteOptions(),
        waypoints: List<LatLng> = emptyList(),
    ): RoutingResult

    /** Recomputes a route from [current] position after a confirmed deviation. */
    suspend fun recalculateRoute(
        region: RegionMetadata?,
        current: LatLng,
        destination: LatLng,
        profile: RoutingProfile = RoutingProfile.CAR,
        options: RouteOptions = RouteOptions(),
    ): RoutingResult
}
