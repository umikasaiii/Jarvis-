package com.simone.jarvismobile.core.navigation

/**
 * Installation lifecycle of an offline region. [CORRUPT] means the payload
 * itself is missing/unreadable; [INCOMPATIBLE] means the files are fine but
 * were built for a manifest schema this build of JARVIS doesn't understand —
 * a different failure with a different fix (update the app, not reinstall
 * the same package).
 */
enum class InstallState { NOT_INSTALLED, DOWNLOADING, PAUSED, VERIFYING, INSTALLED, CORRUPT, INCOMPATIBLE, UPDATE_AVAILABLE }

/**
 * The manifest schema version this build of JARVIS can read. A region built
 * for a different schema is never treated as usable, even if its files are
 * present and its checksum is valid — silently reading an unknown format
 * could misparse bounds/paths instead of failing honestly.
 */
const val SUPPORTED_REGION_SCHEMA_VERSION = 1

/**
 * The independently-versioned data payloads of a region. Kept separate so an
 * update can bump one (e.g. the search index) without re-downloading the rest,
 * and so a corrupt payload can be pinpointed and blocked (spec §18, §19).
 */
data class RegionVersions(
    val mapVersion: Int,
    val routingVersion: Int,
    val searchIndexVersion: Int,
    val schemaVersion: Int,
)

/** Local file names of a region's payloads (under navigation/…/<regionId>/). */
data class RegionFiles(
    val pmtiles: String,
    val routing: String,
    val searchDb: String,
    val style: String,
)

/**
 * Everything known about one downloadable/installed region. This is the metadata
 * index entry the RegionManager persists and the map/route/search layers read.
 * [checksumSha256] is the whole-payload integrity check: a region is only usable
 * once its download has been verified against it.
 */
data class RegionMetadata(
    val id: String,
    val name: String,
    val bounds: GeoBounds,
    val versions: RegionVersions,
    val osmDate: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val files: RegionFiles,
    val state: InstallState = InstallState.NOT_INSTALLED,
) {
    val isUsable: Boolean get() = state == InstallState.INSTALLED || state == InstallState.UPDATE_AVAILABLE
}

/**
 * Picks the installed region that should serve a position: the usable region
 * whose bounds contain the point, preferring the most specific (smallest) one
 * when several overlap. Returns null when the area isn't available offline — the
 * caller then shows "download the map first" rather than falling back to cloud.
 */
object RegionSelector {
    fun regionFor(point: LatLng, regions: List<RegionMetadata>): RegionMetadata? =
        regions.asSequence()
            .filter { it.isUsable && it.bounds.contains(point) }
            .minByOrNull { it.bounds.spanArea }

    /** True when no installed region covers the point (offline gap). */
    fun isCovered(point: LatLng, regions: List<RegionMetadata>): Boolean =
        regionFor(point, regions) != null
}
