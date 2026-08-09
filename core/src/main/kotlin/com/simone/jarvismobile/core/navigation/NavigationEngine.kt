package com.simone.jarvismobile.core.navigation

/** A GPS fix as the navigation core sees it (Android Location is mapped to this). */
data class GpsFix(
    val location: LatLng,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val timestampMs: Long,
)

/** Where the vehicle is on the route after matching. */
data class MatchResult(
    val matched: LatLng,
    val segmentIndex: Int,
    val distanceAlongMeters: Double,
    val offRouteMeters: Double,
)

/**
 * Snaps a raw GPS fix onto the active route rather than showing the noisy raw
 * point. It projects onto every leg and picks the closest, but disambiguates
 * near-ties with the fix's bearing so a parallel road on the other carriageway
 * doesn't steal the match. It never snaps hard when the vehicle has genuinely
 * left the route — [MatchResult.offRouteMeters] carries the real deviation so the
 * off-route detector can decide.
 */
class MapMatcher(private val route: Route) {

    private val cumulative: DoubleArray = DoubleArray(route.geometry.size).also { arr ->
        for (i in 1 until route.geometry.size) {
            arr[i] = arr[i - 1] + Geo.distanceMeters(route.geometry[i - 1], route.geometry[i])
        }
    }

    fun match(fix: GpsFix): MatchResult {
        val g = route.geometry
        var best = -1
        var bestScore = Double.MAX_VALUE
        var bestProj = Geo.Projection(g[0], Double.MAX_VALUE, 0.0)
        for (i in 1 until g.size) {
            val proj = Geo.projectPointToSegment(fix.location, g[i - 1], g[i])
            var score = proj.distanceMeters
            // Prefer a leg whose heading matches the GPS bearing; the weight fades
            // as accuracy worsens so a jittery fix doesn't over-trust bearing.
            val bearing = fix.bearingDegrees
            if (bearing != null) {
                val legBearing = Geo.bearingDegrees(g[i - 1], g[i])
                val delta = Geo.bearingDelta(bearing.toDouble(), legBearing)
                val weight = (1.0 - (fix.accuracyMeters / 60.0)).coerceIn(0.0, 1.0)
                score += weight * (delta / 180.0) * 25.0
            }
            if (score < bestScore) {
                bestScore = score
                best = i - 1
                bestProj = proj
            }
        }
        val along = cumulative[best] + Geo.distanceMeters(g[best], bestProj.point)
        return MatchResult(bestProj.point, best, along, bestProj.distanceMeters)
    }

    fun totalLength(): Double = cumulative.last()
}

/**
 * Confirms the vehicle has really left the route before triggering a recalculation
 * (spec §7): a single GPS wobble must not recalc. A deviation counts only when it
 * exceeds an accuracy-aware threshold; recalculation is signalled after a run of
 * consecutive off-route samples while actually moving.
 */
class OffRouteDetector(
    private val baseThresholdMeters: Double = 35.0,
    private val confirmSamples: Int = 3,
    private val minSpeedMps: Double = 1.5,
) {
    private var consecutive = 0

    /** Feeds one matched sample; returns true when a recalculation is warranted. */
    fun update(match: MatchResult, fix: GpsFix): Boolean {
        val threshold = baseThresholdMeters + fix.accuracyMeters
        val moving = (fix.speedMps ?: 0f) >= minSpeedMps
        consecutive = if (match.offRouteMeters > threshold && moving) consecutive + 1 else 0
        return consecutive >= confirmSamples
    }

    fun reset() { consecutive = 0 }
    val streak: Int get() = consecutive
}

/** Remaining distance, distance to the next maneuver, and ETA — all derived. */
data class NavigationProgress(
    val distanceToManeuverMeters: Double,
    val nextManeuver: Maneuver?,
    val remainingDistanceMeters: Double,
    val etaSeconds: Double,
)

/**
 * Computes live progress from a match. Remaining distance is the route length
 * minus distance travelled; ETA divides the remainder by the best speed estimate
 * (live speed when moving, otherwise the route's nominal speed) so a stop doesn't
 * inflate the arrival time to infinity.
 */
class RouteProgressCalculator(private val route: Route, private val matcher: MapMatcher) {

    fun progress(match: MatchResult, fix: GpsFix): NavigationProgress {
        val total = matcher.totalLength()
        val remaining = (total - match.distanceAlongMeters).coerceAtLeast(0.0)

        val next = route.maneuvers.firstOrNull { m ->
            m.type != ManeuverType.DEPART &&
                cumulativeAt(m.geometryIndex) > match.distanceAlongMeters + 1.0
        }
        val toManeuver = next?.let {
            (cumulativeAt(it.geometryIndex) - match.distanceAlongMeters).coerceAtLeast(0.0)
        } ?: remaining

        val live = fix.speedMps?.toDouble() ?: 0.0
        val speed = if (live >= 1.0) live else route.segments.firstOrNull()?.nominalSpeedMps ?: 13.9
        val eta = if (speed > 0) remaining / speed else 0.0
        return NavigationProgress(toManeuver, next, remaining, eta)
    }

    private fun cumulativeAt(index: Int): Double =
        Geo.polylineLength(route.geometry.subList(0, (index + 1).coerceAtMost(route.geometry.size)))
}

// --- navigation state machine ----------------------------------------------

enum class NavState { IDLE, SEARCHING, ROUTE_READY, NAVIGATING, RECALCULATING, GPS_WEAK, ARRIVED, PAUSED }

sealed interface NavEvent {
    data object SearchStarted : NavEvent
    data object RouteFound : NavEvent
    data object RouteFailed : NavEvent
    data object StartNavigation : NavEvent
    data object OffRouteConfirmed : NavEvent
    data object RecalculationDone : NavEvent
    data object GpsLost : NavEvent
    data object GpsRestored : NavEvent
    data object Arrived : NavEvent
    data object Pause : NavEvent
    data object Resume : NavEvent
    data object Stop : NavEvent
}

/**
 * The one place navigation transitions are decided, mirroring the app's other
 * state machines. It keeps the pre-GPS-loss state so a temporary signal drop
 * returns to exactly where it was instead of aborting the trip (spec §4).
 */
class NavigationStateMachine(initial: NavState = NavState.IDLE) {
    var state: NavState = initial
        private set
    private var beforeGpsLoss: NavState? = null

    fun dispatch(event: NavEvent): NavState {
        state = when (event) {
            NavEvent.Stop -> NavState.IDLE
            NavEvent.SearchStarted -> NavState.SEARCHING
            NavEvent.RouteFound -> NavState.ROUTE_READY
            NavEvent.RouteFailed -> NavState.IDLE
            NavEvent.StartNavigation -> if (state == NavState.ROUTE_READY) NavState.NAVIGATING else state
            NavEvent.OffRouteConfirmed -> if (state == NavState.NAVIGATING) NavState.RECALCULATING else state
            NavEvent.RecalculationDone -> if (state == NavState.RECALCULATING) NavState.NAVIGATING else state
            NavEvent.Arrived -> if (state == NavState.NAVIGATING) NavState.ARRIVED else state
            NavEvent.Pause -> if (state == NavState.NAVIGATING) NavState.PAUSED else state
            NavEvent.Resume -> if (state == NavState.PAUSED) NavState.NAVIGATING else state
            NavEvent.GpsLost -> {
                if (state != NavState.GPS_WEAK) beforeGpsLoss = state
                NavState.GPS_WEAK
            }
            NavEvent.GpsRestored -> beforeGpsLoss?.also { beforeGpsLoss = null } ?: state
        }
        return state
    }
}
