package com.simone.jarvismobile.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationRoutingTest {

    // Three nodes: start (1), a corner (2) east of 1, destination (3) north of 2.
    // A direct toll motorway 1→3 competes with the surface route 1→2→3.
    private val n1 = RoadNode(1, LatLng(41.9000, 12.5000))
    private val n2 = RoadNode(2, LatLng(41.9000, 12.5040))
    private val n3 = RoadNode(3, LatLng(41.9036, 12.5040))

    private fun graph(): RoadGraph = RoadGraph.fromLinks(
        nodes = listOf(n1, n2, n3),
        links = listOf(
            RoadGraph.Link(1, 2, roadName = "Via A", roadClass = "residential", speedKmh = 50.0),
            RoadGraph.Link(2, 3, roadName = "Via B", roadClass = "residential", speedKmh = 50.0),
            RoadGraph.Link(1, 3, roadName = "A1", roadClass = "motorway", speedKmh = 120.0, toll = true),
        ),
    )

    private fun success(r: RoutingResult): Route {
        assertTrue(r is RoutingResult.Success, "expected a route, got $r")
        return (r as RoutingResult.Success).route
    }

    @Test fun fastestUsesTheTollMotorway() {
        val route = success(AStarRouter(graph()).route(n1.point, n3.point))
        assertTrue(route.isValid)
        // Direct hypotenuse ≈ 518 m, well under the 729 m surface route.
        assertTrue(route.distanceMeters in 470.0..570.0, "direct, was ${route.distanceMeters}")
        // No intermediate turn on the direct edge.
        assertEquals(ManeuverType.DEPART, route.maneuvers.first().type)
        assertEquals(ManeuverType.ARRIVE, route.maneuvers.last().type)
    }

    @Test fun avoidTollsReroutesViaSurfaceStreets() {
        val route = success(
            AStarRouter(graph()).route(n1.point, n3.point, options = RouteOptions(avoidTolls = true)),
        )
        assertTrue(route.distanceMeters in 650.0..820.0, "surface route ≈729 m, was ${route.distanceMeters}")
        assertTrue(route.maneuvers.any { it.type == ManeuverType.TURN_LEFT }, "should turn left at the corner")
    }

    @Test fun avoidMotorwaysAlsoReroutes() {
        val route = success(
            AStarRouter(graph()).route(n1.point, n3.point, options = RouteOptions(avoidMotorways = true)),
        )
        assertTrue(route.distanceMeters > 650.0)
    }

    @Test fun noPathReportedNotFaked() {
        // A graph with an unreachable destination node.
        val g = RoadGraph.fromLinks(
            nodes = listOf(n1, n2, n3),
            links = listOf(RoadGraph.Link(1, 2, oneway = true)), // 3 is isolated
        )
        val r = AStarRouter(g).route(n1.point, n3.point)
        assertTrue(r is RoutingResult.Failure)
        assertEquals(RoutingError.NO_PATH, (r as RoutingResult.Failure).error)
    }

    @Test fun emptyGraphFailsCleanly() {
        val r = AStarRouter(RoadGraph(emptyList(), emptyList())).route(n1.point, n3.point)
        assertEquals(RoutingError.NO_GRAPH, (r as RoutingResult.Failure).error)
    }

    @Test fun recalculationRoutesFromCurrentPosition() {
        // Standing near the corner, recalc to the destination → just the last leg.
        val near2 = LatLng(41.9001, 12.5041)
        val route = success(AStarRouter(graph()).recalculate(near2, n3.point, options = RouteOptions(avoidTolls = true)))
        assertTrue(route.distanceMeters in 340.0..470.0, "one leg ≈398 m, was ${route.distanceMeters}")
    }

    @Test fun turnClassificationRightVsLeft() {
        // 1→2 heads east, 2→3 heads north ⇒ a left turn at node 2.
        val edges = listOf(
            RoadEdge(1, 2, listOf(n1.point, n2.point), Geo.distanceMeters(n1.point, n2.point), "Via A"),
            RoadEdge(2, 3, listOf(n2.point, n3.point), Geo.distanceMeters(n2.point, n3.point), "Via B"),
        )
        val geom = listOf(n1.point, n2.point, n3.point)
        val maneuvers = ManeuverGenerator.generate(edges, geom)
        assertEquals(ManeuverType.TURN_LEFT, maneuvers.first { it.type != ManeuverType.DEPART }.type)
    }

    @Test fun waypointIsVisited() {
        val route = success(
            AStarRouter(graph()).route(
                n1.point, n3.point,
                options = RouteOptions(avoidTolls = true),
                waypoints = listOf(n2.point),
            ),
        )
        assertTrue(route.geometry.any { Geo.distanceMeters(it, n2.point) < 5.0 }, "should pass through node 2")
    }
}
