package com.simone.jarvismobile.core.navigation

/** A junction/vertex in the routable road network. */
data class RoadNode(val id: Long, val point: LatLng)

/**
 * A directed drivable stretch between two [RoadNode]s. Non-oneway roads are added
 * to the graph in both directions. Flags let the router honour avoid-options
 * deterministically — the routing decision is never the AI's (spec §6, §19).
 */
data class RoadEdge(
    val from: Long,
    val to: Long,
    val geometry: List<LatLng>,
    val lengthMeters: Double,
    val roadName: String = "",
    val roadClass: String = "residential",
    val speedKmh: Double = 50.0,
    val toll: Boolean = false,
    val ferry: Boolean = false,
) {
    val isMotorway: Boolean get() = roadClass == "motorway" || roadClass == "trunk"
    /** Travel time in seconds at the edge's nominal speed. */
    val timeSeconds: Double get() = if (speedKmh > 0) lengthMeters / (speedKmh / 3.6) else Double.MAX_VALUE
}

/**
 * An in-memory routable graph. Built by the Android layer from a region's routing
 * data (a BRouter `.rd5` or an exported roads file); the pure algorithm here does
 * not care where the edges came from, so it is fully unit-tested.
 */
class RoadGraph(
    val nodes: List<RoadNode>,
    val edges: List<RoadEdge>,
) {
    private val nodeById: Map<Long, RoadNode> = nodes.associateBy { it.id }
    private val outgoing: Map<Long, List<RoadEdge>> = edges.groupBy { it.from }

    fun node(id: Long): RoadNode? = nodeById[id]
    fun edgesFrom(id: Long): List<RoadEdge> = outgoing[id].orEmpty()

    /** The graph node nearest to [point] (great-circle). */
    fun nearestNode(point: LatLng): RoadNode? =
        nodes.minByOrNull { Geo.distanceMeters(point, it.point) }

    val isEmpty: Boolean get() = nodes.isEmpty() || edges.isEmpty()

    companion object {
        /**
         * Builds a graph from undirected road links, splitting a non-oneway link
         * into two directed edges. Lengths are computed from the geometry so the
         * caller only supplies coordinates and attributes.
         */
        fun fromLinks(
            nodes: List<RoadNode>,
            links: List<Link>,
        ): RoadGraph {
            val edges = ArrayList<RoadEdge>(links.size * 2)
            val byId = nodes.associateBy { it.id }
            for (l in links) {
                val a = byId[l.from] ?: continue
                val b = byId[l.to] ?: continue
                val geom = l.geometry.ifEmpty { listOf(a.point, b.point) }
                val len = Geo.polylineLength(geom)
                edges += RoadEdge(l.from, l.to, geom, len, l.roadName, l.roadClass, l.speedKmh, l.toll, l.ferry)
                if (!l.oneway) {
                    edges += RoadEdge(l.to, l.from, geom.reversed(), len, l.roadName, l.roadClass, l.speedKmh, l.toll, l.ferry)
                }
            }
            return RoadGraph(nodes, edges)
        }
    }

    /** An undirected input link (before oneway expansion). */
    data class Link(
        val from: Long,
        val to: Long,
        val geometry: List<LatLng> = emptyList(),
        val roadName: String = "",
        val roadClass: String = "residential",
        val speedKmh: Double = 50.0,
        val oneway: Boolean = false,
        val toll: Boolean = false,
        val ferry: Boolean = false,
    )
}
