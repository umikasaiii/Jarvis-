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

    /** Null with no live fix yet — the caller should keep whatever camera state it already has. */
    fun forFollow(fix: GpsFix?): DrivingCameraState? {
        val f = fix ?: return null
        val speedKmh = (f.speedMps ?: 0f) * 3.6
        val zoomOutFraction = (speedKmh / ZOOM_OUT_SPEED_KMH).coerceIn(0.0, 1.0)
        val zoom = MAX_ZOOM - (MAX_ZOOM - MIN_ZOOM) * zoomOutFraction
        return DrivingCameraState(
            bearingDegrees = f.bearingDegrees ?: 0f,
            tiltDegrees = FOLLOW_TILT_DEGREES,
            zoom = zoom,
        )
    }
}
