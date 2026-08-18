package com.simone.jarvismobile.core.driving.golden

/**
 * A rectangle expressed as fractions (0f–1f) of the golden reference's own
 * width/height, not absolute pixels — the golden reference (a 941×1672
 * iPhone-shaped composite mockup, screen content cropped to 845×1620) is not
 * the resolution of any real Android device, so absolute reference pixels
 * never transfer directly. A fraction does: multiplied by whatever container
 * size the real screen actually has, it reproduces the same *proportion* of
 * the layout the reference shows, which is the only thing that can be
 * faithful across phones of different sizes.
 */
data class GoldenRect(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
) {
    init {
        require(xFraction in 0f..1f) { "xFraction out of range: $xFraction" }
        require(yFraction in 0f..1f) { "yFraction out of range: $yFraction" }
        require(widthFraction in 0f..1f) { "widthFraction out of range: $widthFraction" }
        require(heightFraction in 0f..1f) { "heightFraction out of range: $heightFraction" }
        require(xFraction + widthFraction <= 1f + 1e-4f) { "rect overflows right edge: $this" }
        require(yFraction + heightFraction <= 1f + 1e-4f) { "rect overflows bottom edge: $this" }
    }

    /** Resolves this rect against a real container size, in whatever unit [containerWidth]/[containerHeight] are (px or dp). */
    fun resolve(containerWidth: Float, containerHeight: Float): ResolvedRect = ResolvedRect(
        x = xFraction * containerWidth,
        y = yFraction * containerHeight,
        width = widthFraction * containerWidth,
        height = heightFraction * containerHeight,
    )
}

/** A single point expressed as a fraction of the golden reference (vehicle anchor, turn marker center, button center). */
data class GoldenPoint(val xFraction: Float, val yFraction: Float) {
    init {
        require(xFraction in 0f..1f) { "xFraction out of range: $xFraction" }
        require(yFraction in 0f..1f) { "yFraction out of range: $yFraction" }
    }

    fun resolve(containerWidth: Float, containerHeight: Float): ResolvedPoint =
        ResolvedPoint(x = xFraction * containerWidth, y = yFraction * containerHeight)
}

data class ResolvedRect(val x: Float, val y: Float, val width: Float, val height: Float)
data class ResolvedPoint(val x: Float, val y: Float)

/**
 * Where the golden reference image itself should be drawn inside a container
 * of a different aspect ratio ([ReferenceOverlay][com.simone.jarvismobile.ui.driving.golden.JarvisDriveReferenceOverlay]
 * needs this): the reference is letterboxed/pillarboxed — scaled uniformly
 * to fit, centered — never stretched, so its own internal proportions (and
 * therefore the [GoldenRect]s measured from it) stay geometrically valid.
 */
data class FittedReference(val x: Float, val y: Float, val width: Float, val height: Float, val scale: Float)

/**
 * Real reference-pixel measurements from `docs/design/jarvis_drive_reference.png`,
 * cropped to just the phone's screen content (the composite includes a black
 * phone bezel around it, which is not part of the UI). Measured by cropping
 * candidate regions and visually re-inspecting them against a pixel grid
 * overlaid on the screen crop — not eyeballed from the full composite, and
 * not the 864×1536 figure guessed before this was actually measured.
 *
 * Every component rect below is [screen pixel rect] / [REFERENCE_WIDTH or
 * REFERENCE_HEIGHT], to one grid-line's precision (50px) — a real refactor
 * pass should re-measure at pixel accuracy once fine differences matter, but
 * these are grounded in the actual artwork, not invented, which is the
 * REGOLA 1-3 requirement this file exists to satisfy.
 */
object JarvisDriveGoldenLayout {
    /** Screen content area cropped from the golden reference composite, in reference pixels. */
    const val REFERENCE_WIDTH = 845f
    const val REFERENCE_HEIGHT = 1620f

    val TopHud = GoldenRect(20f / REFERENCE_WIDTH, 75f / REFERENCE_HEIGHT, 805f / REFERENCE_WIDTH, 75f / REFERENCE_HEIGHT)
    val ManeuverCard = GoldenRect(20f / REFERENCE_WIDTH, 270f / REFERENCE_HEIGHT, 395f / REFERENCE_WIDTH, 190f / REFERENCE_HEIGHT)
    val SpeedCard = GoldenRect(685f / REFERENCE_WIDTH, 270f / REFERENCE_HEIGHT, 140f / REFERENCE_WIDTH, 165f / REFERENCE_HEIGHT)
    val EtaBar = GoldenRect(20f / REFERENCE_WIDTH, 470f / REFERENCE_HEIGHT, 780f / REFERENCE_WIDTH, 75f / REFERENCE_HEIGHT)
    val MessagesCard = GoldenRect(10f / REFERENCE_WIDTH, 840f / REFERENCE_HEIGHT, 165f / REFERENCE_WIDTH, 235f / REFERENCE_HEIGHT)
    val MusicCard = GoldenRect(655f / REFERENCE_WIDTH, 840f / REFERENCE_HEIGHT, 170f / REFERENCE_WIDTH, 230f / REFERENCE_HEIGHT)
    val VoiceDock = GoldenRect(105f / REFERENCE_WIDTH, 1300f / REFERENCE_HEIGHT, 610f / REFERENCE_WIDTH, 200f / REFERENCE_HEIGHT)

    /** Map viewport: everything the top HUD doesn't cover, edge to edge. */
    val MapViewport = GoldenRect(0f, 75f / REFERENCE_HEIGHT, 1f, 1f - 75f / REFERENCE_HEIGHT)

    val AppButtonCenter = GoldenPoint(65f / REFERENCE_WIDTH, 1510f / REFERENCE_HEIGHT)
    val LayersButtonCenter = GoldenPoint(765f / REFERENCE_WIDTH, 1510f / REFERENCE_HEIGHT)
    val VehicleAnchor = GoldenPoint(410f / REFERENCE_WIDTH, 1125f / REFERENCE_HEIGHT)
    val TurnMarkerCenter = GoldenPoint(365f / REFERENCE_WIDTH, 625f / REFERENCE_HEIGHT)

    /** Radius as a fraction of [REFERENCE_WIDTH], for the two round buttons and the two circular markers. */
    const val ROUND_BUTTON_RADIUS_FRACTION = 40f / REFERENCE_WIDTH
    const val TURN_MARKER_RADIUS_FRACTION = 35f / REFERENCE_WIDTH
    const val VEHICLE_MARKER_RING_RADIUS_FRACTION = 55f / REFERENCE_WIDTH

    /** Every named rect, for [VisualDebug][com.simone.jarvismobile.ui.driving.golden.JarvisDriveVisualDebug] to iterate and label. */
    val namedRects: List<Pair<String, GoldenRect>> = listOf(
        "TopHud" to TopHud,
        "ManeuverCard" to ManeuverCard,
        "SpeedCard" to SpeedCard,
        "EtaBar" to EtaBar,
        "MessagesCard" to MessagesCard,
        "MusicCard" to MusicCard,
        "VoiceDock" to VoiceDock,
        "MapViewport" to MapViewport,
    )

    val namedPoints: List<Pair<String, GoldenPoint>> = listOf(
        "AppButtonCenter" to AppButtonCenter,
        "LayersButtonCenter" to LayersButtonCenter,
        "VehicleAnchor" to VehicleAnchor,
        "TurnMarkerCenter" to TurnMarkerCenter,
    )

    /**
     * Uniform scale-to-fit (never stretch) of the [REFERENCE_WIDTH]×[REFERENCE_HEIGHT]
     * reference image inside a container of a possibly different aspect
     * ratio, centered — the standard "letterbox" fit, so the reference's own
     * geometry stays undistorted when overlaid on a differently-shaped phone
     * screen.
     */
    fun fitReference(containerWidth: Float, containerHeight: Float): FittedReference {
        val scale = minOf(containerWidth / REFERENCE_WIDTH, containerHeight / REFERENCE_HEIGHT)
        val width = REFERENCE_WIDTH * scale
        val height = REFERENCE_HEIGHT * scale
        return FittedReference(
            x = (containerWidth - width) / 2f,
            y = (containerHeight - height) / 2f,
            width = width,
            height = height,
            scale = scale,
        )
    }
}
