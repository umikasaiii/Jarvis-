package com.simone.jarvismobile.core.navigation

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 coordinate. Pure data; no Android Location dependency. */
data class LatLng(val lat: Double, val lon: Double)

/** A geographic bounding box (min/max lat/lon). Used to pick the right region. */
data class GeoBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    fun contains(p: LatLng): Boolean =
        p.lat in minLat..maxLat && p.lon in minLon..maxLon

    /** Rough area proxy (deg²) so the smallest containing region can be preferred. */
    val spanArea: Double get() = (maxLat - minLat) * (maxLon - minLon)

    val center: LatLng get() = LatLng((minLat + maxLat) / 2, (minLon + maxLon) / 2)
}

/**
 * Deterministic geodesy used across navigation: distances, bearings and the
 * point-to-segment projection that map matching and progress rely on. Everything
 * is local, offline and unit-tested — no Play Services, no network.
 */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG = Math.PI / 180.0

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = (b.lat - a.lat) * DEG
        val dLon = (b.lon - a.lon) * DEG
        val la1 = a.lat * DEG
        val la2 = b.lat * DEG
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing a→b in degrees [0,360). */
    fun bearingDegrees(a: LatLng, b: LatLng): Double {
        val la1 = a.lat * DEG
        val la2 = b.lat * DEG
        val dLon = (b.lon - a.lon) * DEG
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        val deg = atan2(y, x) / DEG
        return (deg + 360.0) % 360.0
    }

    /** Smallest absolute difference between two bearings, in degrees [0,180]. */
    fun bearingDelta(a: Double, b: Double): Double {
        val d = abs((a - b + 180.0) % 360.0 - 180.0)
        return if (d > 180.0) 360.0 - d else d
    }

    /** The projection of [p] onto segment [a]-[b]. */
    data class Projection(val point: LatLng, val distanceMeters: Double, val t: Double)

    /**
     * Projects [p] onto the segment a→b in a local planar frame (equirectangular
     * around the segment), good to a few centimetres at street scale. [t] is the
     * clamped position along the segment in [0,1].
     */
    fun projectPointToSegment(p: LatLng, a: LatLng, b: LatLng): Projection {
        // Local metres relative to a, scaling longitude by cos(lat).
        val latRef = a.lat * DEG
        val kx = cos(latRef) * EARTH_RADIUS_M * DEG
        val ky = EARTH_RADIUS_M * DEG
        val ax = 0.0
        val ay = 0.0
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx
        val py = (p.lat - a.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        val projLatLng = LatLng(a.lat + projY / ky, a.lon + projX / kx)
        val dist = distanceMeters(p, projLatLng)
        return Projection(projLatLng, dist, t)
    }

    /** Total length in metres of a polyline. */
    fun polylineLength(points: List<LatLng>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until points.size) sum += distanceMeters(points[i - 1], points[i])
        return sum
    }
}
