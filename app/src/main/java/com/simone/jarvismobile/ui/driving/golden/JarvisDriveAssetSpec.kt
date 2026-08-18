package com.simone.jarvismobile.ui.driving.golden

/** How a JARVIS Drive visual element is meant to be rendered. */
enum class JarvisDriveAssetKind {
    /** A transparent PNG frame/texture, used as a literal background (spec §5, §2). */
    TRANSPARENT_PNG,

    /** An Android 9-patch — not used anywhere yet; the current PNGs are fixed-aspect frames, not stretchable content areas. */
    NINE_PATCH,

    /** A VectorDrawable/SVG — not used anywhere yet; every current asset is a raster PNG from the reference kit. */
    VECTOR,

    /** Drawn live in Compose: dynamic text, values, buttons, or simple geometry with no reference asset. */
    COMPOSE_DYNAMIC,

    /** Rendered by MapLibre from vector tile data, not a bitmap asset at all. */
    MAP_DATA,
}

/**
 * One entry in the classification table spec §5 asks for: what kind of
 * asset backs a given JARVIS Drive visual element, and — for real files —
 * where it lives. Android's flat `res/drawable*/` resource system doesn't
 * support subfolders (AAPT rejects them), so "a clear directory" here means
 * a consistent name prefix inside `res/drawable-nodpi/` (`drive_hud_*` for
 * this batch) rather than a `res/drawable/jarvis_drive/` path, which would
 * not build.
 */
data class JarvisDriveAssetEntry(
    val component: String,
    val kind: JarvisDriveAssetKind,
    val resourceName: String?,
    val note: String,
)

/**
 * The full classification table (spec §5 "Classifica ogni elemento").
 * [JarvisDriveAssets] holds the actual resource ids for the five real PNGs;
 * this is the human-readable audit of every component, including the ones
 * that are still Compose-only.
 */
object JarvisDriveAssetSpec {
    val entries: List<JarvisDriveAssetEntry> = listOf(
        JarvisDriveAssetEntry("TopHud", JarvisDriveAssetKind.COMPOSE_DYNAMIC, null, "No clean textless reference sprite exists yet for the header; see JarvisDriveAssets.kt."),
        JarvisDriveAssetEntry("ManeuverCard", JarvisDriveAssetKind.TRANSPARENT_PNG, "drive_hud_maneuver", "Frame from the reference kit; distance/road name/glyph drawn on top in Compose."),
        JarvisDriveAssetEntry("SpeedCard", JarvisDriveAssetKind.TRANSPARENT_PNG, "drive_hud_speed", "Frame from the reference kit; limit/current speed drawn on top in Compose."),
        JarvisDriveAssetEntry("EtaBar", JarvisDriveAssetKind.TRANSPARENT_PNG, "drive_hud_eta", "Frame from the reference kit; arrival/min/distance + stop button drawn on top in Compose."),
        JarvisDriveAssetEntry("MessagesCard", JarvisDriveAssetKind.TRANSPARENT_PNG, "drive_hud_messages", "Frame from the reference kit; sender/preview/count drawn on top in Compose."),
        JarvisDriveAssetEntry("MusicCard", JarvisDriveAssetKind.TRANSPARENT_PNG, "drive_hud_music", "Frame from the reference kit; title/artist/art/transport drawn on top in Compose."),
        JarvisDriveAssetEntry("VoiceDock", JarvisDriveAssetKind.COMPOSE_DYNAMIC, null, "The one reference sprite for this has \"Listening\" and the mic glow baked in — unusable for the dock's five live voice states. Shape/gradient/notch are Compose, matched to the reference by eye."),
        JarvisDriveAssetEntry("VehicleMarker", JarvisDriveAssetKind.COMPOSE_DYNAMIC, null, "Rotates live with GPS bearing (VehiclePuck) — inherently dynamic, not a static asset."),
        JarvisDriveAssetEntry("TurnMarker", JarvisDriveAssetKind.COMPOSE_DYNAMIC, null, "New this pass (JarvisDriveTurnMarker); glyph varies by maneuver type."),
        JarvisDriveAssetEntry("AppButton", JarvisDriveAssetKind.COMPOSE_DYNAMIC, null, "New this pass (JarvisDriveAppButton), same chrome as LayersButton; simple circular geometry, no asset needed."),
        JarvisDriveAssetEntry("LayersButton", JarvisDriveAssetKind.COMPOSE_DYNAMIC, null, "Existing (VoiceDock.kt); simple circular geometry, no asset needed."),
        JarvisDriveAssetEntry("Map + Route", JarvisDriveAssetKind.MAP_DATA, null, "MapLibre style (jarvis-navigation.json), never a bitmap."),
        JarvisDriveAssetEntry("Golden reference (debug overlay)", JarvisDriveAssetKind.TRANSPARENT_PNG, "jarvis_drive_golden_reference", "The golden reference itself, screen-cropped, for JarvisDriveReferenceOverlay — not part of the production UI."),
    )
}
