package com.simone.jarvismobile.core.navigation

import java.util.PriorityQueue

/** Why a route could not be produced (never a silent fake — spec §16, §19). */
enum class RoutingError { NO_GRAPH, NO_START, NO_DESTINATION, NO_PATH }

sealed interface RoutingResult {
    data class Success(val route: Route) : RoutingResult
    data class Failure(val error: RoutingError) : RoutingResult
}

/**
 * Deterministic offline routing with A* over a [RoadGraph]. The cost is travel
 * time (preferFastest) or distance (preferShortest); avoid-options prune tolls,
 * motorways and ferries. The heuristic is admissible (straight-line distance,
 * divided by the graph's top speed for the time metric), so A* returns an optimal
 * path. Nothing here touches the network or the AI model.
 */
class AStarRouter(private val graph: RoadGraph) {

    private val maxSpeedMps: Double =
        (graph.edges.maxOfOrNull { it.speedKmh } ?: 130.0).coerceAtLeast(1.0) / 3.6

    fun route(
        start: LatLng,
        destination: LatLng,
        profile: RoutingProfile = RoutingProfile.CAR,
        options: RouteOptions = RouteOptions(),
        waypoints: List<LatLng> = emptyList(),
    ): RoutingResult {
        if (graph.isEmpty) return RoutingResult.Failure(RoutingError.NO_GRAPH)
        val stops = (listOf(start) + waypoints + destination)
        val nodeIds = stops.map { graph.nearestNode(it)?.id ?: return RoutingResult.Failure(RoutingError.NO_START) }

        val fullEdges = ArrayList<RoadEdge>()
        for (i in 1 until nodeIds.size) {
            val leg = shortestPath(nodeIds[i - 1], nodeIds[i], options)
                ?: return RoutingResult.Failure(RoutingError.NO_PATH)
            fullEdges += leg
        }
        if (fullEdges.isEmpty()) return RoutingResult.Failure(RoutingError.NO_PATH)

        val geometry = stitchGeometry(fullEdges)
        val maneuvers = ManeuverGenerator.generate(fullEdges, geometry)
        val destNode = graph.node(nodeIds.last())!!.point
        val nominal = fullEdges.map { it.speedKmh }.average().coerceAtLeast(5.0) / 3.6
        return RoutingResult.Success(
            Route.fromGeometry(geometry, maneuvers, destNode, profile, nominal),
        )
    }

    /** Recalculation is just a fresh route from the current position. */
    fun recalculate(
        current: LatLng,
        destination: LatLng,
        profile: RoutingProfile = RoutingProfile.CAR,
        options: RouteOptions = RouteOptions(),
    ): RoutingResult = route(current, destination, profile, options)

    private fun allowed(edge: RoadEdge, options: RouteOptions): Boolean {
        if (options.avoidTolls && edge.toll) return false
        if (options.avoidMotorways && edge.isMotorway) return false
        if (options.avoidFerries && edge.ferry) return false
        return true
    }

    private fun cost(edge: RoadEdge, options: RouteOptions): Double =
        if (options.preferShortest) edge.lengthMeters else edge.timeSeconds

    private fun heuristic(from: Long, goalPoint: LatLng, options: RouteOptions): Double {
        val p = graph.node(from)?.point ?: return 0.0
        val d = Geo.distanceMeters(p, goalPoint)
        return if (options.preferShortest) d else d / maxSpeedMps
    }

    private fun shortestPath(startId: Long, goalId: Long, options: RouteOptions): List<RoadEdge>? {
        if (startId == goalId) return emptyList()
        val goalPoint = graph.node(goalId)?.point ?: return null
        val g = HashMap<Long, Double>().apply { put(startId, 0.0) }
        val cameFrom = HashMap<Long, RoadEdge>()
        val open = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        open.add(startId to heuristic(startId, goalPoint, options))
        val closed = HashSet<Long>()

        while (open.isNotEmpty()) {
            val (current, _) = open.poll()
            if (current == goalId) return reconstruct(cameFrom, goalId)
            if (!closed.add(current)) continue
            val gc = g[current] ?: continue
            for (edge in graph.edgesFrom(current)) {
                if (!allowed(edge, options)) continue
                val tentative = gc + cost(edge, options)
                if (tentative < (g[edge.to] ?: Double.MAX_VALUE)) {
                    g[edge.to] = tentative
                    cameFrom[edge.to] = edge
                    open.add(edge.to to tentative + heuristic(edge.to, goalPoint, options))
                }
            }
        }
        return null
    }

    private fun reconstruct(cameFrom: Map<Long, RoadEdge>, goalId: Long): List<RoadEdge> {
        val path = ArrayList<RoadEdge>()
        var node = goalId
        while (true) {
            val edge = cameFrom[node] ?: break
            path += edge
            node = edge.from
        }
        return path.reversed()
    }

    private fun stitchGeometry(edges: List<RoadEdge>): List<LatLng> {
        val out = ArrayList<LatLng>()
        for ((i, e) in edges.withIndex()) {
            val pts = if (i == 0) e.geometry else e.geometry.drop(1) // avoid duplicating shared node
            out += pts
        }
        return out
    }
}
