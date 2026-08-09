package com.simone.jarvismobile.core.navigation

/** Vehicle profile for routing. Maps to a BRouter profile in the Android layer. */
enum class RoutingProfile { CAR, MOTORCYCLE, BICYCLE, WALKING }

/** Route preferences. Deterministic inputs to the routing engine, never the LLM. */
data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
    val avoidFerries: Boolean = false,
    val preferShortest: Boolean = false,
)

/** The kind of turn/action at a maneuver point. */
enum class ManeuverType {
    DEPART, CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, KEEP_LEFT, KEEP_RIGHT, UTURN, ROUNDABOUT, ARRIVE,
}

/**
 * One instruction along the route. Distances/directions come only from the
 * computed route — the AI may read them aloud but must never change them (§19).
 * [roundaboutExit] is 1-based when [type] is ROUNDABOUT.
 */
data class Maneuver(
    val type: ManeuverType,
    val at: LatLng,
    /** Index into [Route.geometry] where this maneuver occurs. */
    val geometryIndex: Int,
    val roadName: String = "",
    val roundaboutExit: Int? = null,
)

/** A drivable stretch between two maneuvers, with its own speed for ETA. */
data class RouteSegment(
    val startIndex: Int,
    val endIndex: Int,
    val distanceMeters: Double,
    val roadName: String = "",
    /** Nominal speed used for ETA when no live speed is available (m/s). */
    val nominalSpeedMps: Double = 13.9,
)

/**
 * A computed route: the polyline plus the derived maneuvers, segments and totals.
 * Produced by the offline engine; consumed by matching, progress and voice.
 */
data class Route(
    val geometry: List<LatLng>,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Double,
    val maneuvers: List<Maneuver>,
    val segments: List<RouteSegment>,
    val destination: LatLng,
    val profile: RoutingProfile = RoutingProfile.CAR,
) {
    val isValid: Boolean
        get() = geometry.size >= 2 && distanceMeters > 0 && maneuvers.isNotEmpty()

    companion object {
        /**
         * Builds a [Route] from a bare polyline and maneuver anchor indices,
         * filling in per-segment distances and the total. Used by the routing
         * wrapper so the Android engine only has to supply geometry + turn points.
         */
        fun fromGeometry(
            geometry: List<LatLng>,
            maneuvers: List<Maneuver>,
            destination: LatLng,
            profile: RoutingProfile = RoutingProfile.CAR,
            nominalSpeedMps: Double = 13.9,
        ): Route {
            require(geometry.size >= 2) { "route needs at least two points" }
            val total = Geo.polylineLength(geometry)
            val anchors = (listOf(0) + maneuvers.map { it.geometryIndex } + listOf(geometry.lastIndex))
                .distinct().sorted()
            val segments = ArrayList<RouteSegment>()
            for (i in 1 until anchors.size) {
                val a = anchors[i - 1]
                val b = anchors[i]
                val dist = Geo.polylineLength(geometry.subList(a, b + 1))
                segments += RouteSegment(a, b, dist, nominalSpeedMps = nominalSpeedMps)
            }
            val duration = if (nominalSpeedMps > 0) total / nominalSpeedMps else 0.0
            return Route(geometry, total, duration, maneuvers, segments, destination, profile)
        }
    }
}

/** A place the user can navigate to; the ONLY source of destination coordinates. */
data class Place(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val location: LatLng,
    /** OSM-style prominence 0..1 (a city > a shop), used in ranking. */
    val importance: Double = 0.3,
    val address: String = "",
)

enum class PlaceCategory {
    CITY, TOWN, VILLAGE, STREET, ADDRESS, FUEL, PARKING, RESTAURANT, BAR, SHOP,
    HOSPITAL, PHARMACY, POLICE, HOTEL, STATION, AIRPORT, TOURISM, OTHER,
}
