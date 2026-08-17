package com.simone.jarvismobile.core.navigation

/**
 * A small, deterministic synthetic drive loop for debug-only GPS simulation
 * (Driving Mode V2 spec §13) — a square loop around [origin], so Driving
 * Mode can be exercised without a real GPS fix. The app layer is the one
 * that decides *whether* this ever runs, gated behind `BuildConfig.DEBUG`
 * ([com.simone.jarvismobile.navigation.NavigationLocationProvider]); this
 * class itself is just the pure math, testable with no Android dependency.
 */
class SimulatedGpsRoute(private val origin: LatLng) {

    private val waypoints: List<LatLng> = listOf(
        origin,
        offset(origin, LEG_METERS, 0.0),
        offset(origin, LEG_METERS, LEG_METERS),
        offset(origin, 0.0, LEG_METERS),
        origin,
    )

    /** The simulated fix after [elapsedMs] of driving the loop at [speedMps]. */
    fun fixAt(elapsedMs: Long, speedMps: Float = DEFAULT_SPEED_MPS): GpsFix {
        val totalLength = LEG_METERS * 4
        var remaining = (speedMps * (elapsedMs / 1000.0)).mod(totalLength)
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            val segmentLength = Geo.distanceMeters(a, b)
            val isLastSegment = i == waypoints.size - 2
            if (remaining <= segmentLength || isLastSegment) {
                val fraction = if (segmentLength > 0) (remaining / segmentLength).coerceIn(0.0, 1.0) else 0.0
                return GpsFix(
                    location = LatLng(
                        a.lat + (b.lat - a.lat) * fraction,
                        a.lon + (b.lon - a.lon) * fraction,
                    ),
                    accuracyMeters = SIMULATED_ACCURACY_M,
                    speedMps = speedMps,
                    bearingDegrees = Geo.bearingDegrees(a, b).toFloat(),
                    timestampMs = elapsedMs,
                )
            }
            remaining -= segmentLength
        }
        return GpsFix(origin, SIMULATED_ACCURACY_M, speedMps, 0f, elapsedMs)
    }

    private companion object {
        const val LEG_METERS = 300.0
        const val DEFAULT_SPEED_MPS = 12f
        const val SIMULATED_ACCURACY_M = 5f
        const val METERS_PER_DEGREE_LAT = 111_320.0

        fun offset(from: LatLng, northMeters: Double, eastMeters: Double): LatLng {
            val dLat = northMeters / METERS_PER_DEGREE_LAT
            val dLon = eastMeters / (METERS_PER_DEGREE_LAT * kotlin.math.cos(Math.toRadians(from.lat)))
            return LatLng(from.lat + dLat, from.lon + dLon)
        }
    }
}
