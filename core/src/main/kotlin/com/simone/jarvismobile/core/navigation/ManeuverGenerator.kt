package com.simone.jarvismobile.core.navigation

import kotlin.math.abs

/**
 * Derives turn-by-turn maneuvers from a routed path. The instruction at each
 * junction comes only from geometry — the signed angle between the incoming and
 * outgoing segment — and the road name from the route data. Nothing is invented:
 * the AI voice later reads these, it does not create them (spec §11, §19).
 */
object ManeuverGenerator {

    /** Below this turn angle (degrees) a junction is "continue", not a maneuver. */
    private const val TURN_THRESHOLD = 22.0

    fun generate(edges: List<RoadEdge>, geometry: List<LatLng>): List<Maneuver> {
        if (edges.isEmpty() || geometry.size < 2) return emptyList()
        val out = ArrayList<Maneuver>()
        out += Maneuver(ManeuverType.DEPART, geometry.first(), 0, edges.first().roadName)

        // Track the geometry index at each junction between consecutive edges.
        var index = edges.first().geometry.size - 1
        for (i in 1 until edges.size) {
            val prev = edges[i - 1]
            val next = edges[i]
            val inB = lastBearing(prev.geometry)
            val outB = firstBearing(next.geometry)
            if (inB != null && outB != null) {
                val delta = signedDelta(inB, outB)
                val type = classify(delta)
                val nameChanged = next.roadName.isNotBlank() && next.roadName != prev.roadName
                if (type != ManeuverType.CONTINUE || nameChanged) {
                    out += Maneuver(
                        type = if (type == ManeuverType.CONTINUE) ManeuverType.CONTINUE else type,
                        at = geometry.getOrElse(index) { next.geometry.first() },
                        geometryIndex = index,
                        roadName = next.roadName,
                    )
                }
            }
            index += next.geometry.size - 1
        }

        out += Maneuver(ManeuverType.ARRIVE, geometry.last(), geometry.lastIndex, edges.last().roadName)
        return out
    }

    private fun classify(delta: Double): ManeuverType {
        val a = abs(delta)
        val right = delta > 0
        return when {
            a < TURN_THRESHOLD -> ManeuverType.CONTINUE
            a < 45 -> if (right) ManeuverType.SLIGHT_RIGHT else ManeuverType.SLIGHT_LEFT
            a < 135 -> if (right) ManeuverType.TURN_RIGHT else ManeuverType.TURN_LEFT
            a < 170 -> if (right) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT
            else -> ManeuverType.UTURN
        }
    }

    /** Signed bearing difference in (-180,180]; positive = turning right. */
    private fun signedDelta(inB: Double, outB: Double): Double {
        var d = (outB - inB + 540.0) % 360.0 - 180.0
        if (d <= -180.0) d += 360.0
        return d
    }

    private fun lastBearing(geom: List<LatLng>): Double? {
        if (geom.size < 2) return null
        return Geo.bearingDegrees(geom[geom.size - 2], geom[geom.size - 1])
    }

    private fun firstBearing(geom: List<LatLng>): Double? {
        if (geom.size < 2) return null
        return Geo.bearingDegrees(geom[0], geom[1])
    }
}
