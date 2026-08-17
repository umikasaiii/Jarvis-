package com.simone.jarvismobile.core.driving

import com.simone.jarvismobile.core.navigation.GpsFix

/** Which camera behavior `JarvisMapView` should use right now (spec §12). */
enum class DrivingCameraMode { FOLLOW, FREE, OVERVIEW }

/**
 * Holds which [DrivingCameraMode] the map is in. A real touch-drag from the
 * driver always drops FOLLOW into FREE — recentering is the only way back,
 * never automatic — so a manual pan is never immediately fought by the next
 * GPS fix. [OVERVIEW] is a declared placeholder: showing "the whole route"
 * needs a real route to fit bounds around, which this phase doesn't compute
 * yet (spec: no Valhalla/turn-by-turn here).
 */
data class DrivingCameraUiState(val mode: DrivingCameraMode = DrivingCameraMode.FOLLOW) {
    /** A user gesture only matters while following — panning while already free is a no-op. */
    fun userPanned(): DrivingCameraUiState =
        if (mode == DrivingCameraMode.FOLLOW) copy(mode = DrivingCameraMode.FREE) else this

    fun recenter(): DrivingCameraUiState = copy(mode = DrivingCameraMode.FOLLOW)

    fun overview(): DrivingCameraUiState = copy(mode = DrivingCameraMode.OVERVIEW)
}

/**
 * Camera parameters for the follow-mode navigation view (spec §8): bearing
 * oriented to the direction of travel (heading-up), a fixed navigation tilt,
 * and a speed-scaled zoom so faster driving shows more road ahead. A pure
 * calculator — `JarvisMapView` is still the only thing that turns this into
 * an actual MapLibre camera move.
 */
data class DrivingCameraState(
    val bearingDegrees: Float,
    val tiltDegrees: Float,
    val zoom: Double,
)

object DrivingCameraController {
    private const val FOLLOW_TILT_DEGREES = 55f
    private const val MIN_ZOOM = 15.5
    private const val MAX_ZOOM = 18.0

    /** Speed at or above which the camera reaches its most zoomed-out follow level. */
    private const val ZOOM_OUT_SPEED_KMH = 90.0

    /** Distance at which the next-maneuver zoom-in boost starts ramping up. */
    private const val MANEUVER_ZOOM_DISTANCE_M = 180.0

    /** Extra zoom levels added at zero distance from the maneuver, on top of the speed-based zoom. */
    private const val MANEUVER_ZOOM_BOOST = 1.0

    /**
     * Null with no live fix yet — the caller should keep whatever camera state it already has.
     * [distanceToManeuverMeters] (null with no upcoming turn) nudges the camera in closer as the
     * driver approaches it, on top of — never instead of — the speed-based zoom, so a fast
     * approach to a turn still zooms in rather than staying zoomed out.
     */
    fun forFollow(fix: GpsFix?, distanceToManeuverMeters: Double? = null): DrivingCameraState? {
        val f = fix ?: return null
        val speedKmh = (f.speedMps ?: 0f) * 3.6
        val zoomOutFraction = (speedKmh / ZOOM_OUT_SPEED_KMH).coerceIn(0.0, 1.0)
        val speedZoom = MAX_ZOOM - (MAX_ZOOM - MIN_ZOOM) * zoomOutFraction
        val proximityFraction = distanceToManeuverMeters
            ?.let { (1.0 - (it / MANEUVER_ZOOM_DISTANCE_M)).coerceIn(0.0, 1.0) }
            ?: 0.0
        val zoom = speedZoom + MANEUVER_ZOOM_BOOST * proximityFraction
        return DrivingCameraState(
            bearingDegrees = f.bearingDegrees ?: 0f,
            tiltDegrees = FOLLOW_TILT_DEGREES,
            zoom = zoom,
        )
    }
}
