package com.simone.jarvismobile.core.navigation

/**
 * The fields JARVIS needs out of a PMTiles v3 archive header: the geographic
 * bounds (so [RegionSelector] can tell which region covers a position) and the
 * zoom range. Parsing the 127-byte fixed header is pure and deterministic, so it
 * lives in `:core` and is unit-tested — the Android layer just reads the first
 * 127 bytes off the file and hands them here.
 *
 * Reference: PMTiles v3 spec (little-endian; lon/lat are int32 in E7 degrees).
 */
data class PmtilesHeader(
    val bounds: GeoBounds,
    val center: LatLng,
    val minZoom: Int,
    val maxZoom: Int,
)

object PmtilesHeaderParser {
    const val HEADER_SIZE = 127
    private const val MAGIC = "PMTiles"

    /** Parses [bytes] (≥127) or returns null if it isn't a valid PMTiles v3 header. */
    fun parse(bytes: ByteArray): PmtilesHeader? {
        if (bytes.size < HEADER_SIZE) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i].code.toByte()) return null
        if (bytes[7].toInt() != 3) return null

        val minZoom = bytes[100].toInt() and 0xff
        val maxZoom = bytes[101].toInt() and 0xff
        val minLon = i32le(bytes, 102) / 1e7
        val minLat = i32le(bytes, 106) / 1e7
        val maxLon = i32le(bytes, 110) / 1e7
        val maxLat = i32le(bytes, 114) / 1e7
        val centerLon = i32le(bytes, 119) / 1e7
        val centerLat = i32le(bytes, 123) / 1e7

        // Guard against a corrupt header producing nonsense coordinates.
        if (minLat !in -90.0..90.0 || maxLat !in -90.0..90.0 ||
            minLon !in -180.0..180.0 || maxLon !in -180.0..180.0 ||
            minLat > maxLat || minLon > maxLon
        ) {
            return null
        }

        return PmtilesHeader(
            bounds = GeoBounds(minLat, minLon, maxLat, maxLon),
            center = LatLng(centerLat, centerLon),
            minZoom = minZoom,
            maxZoom = maxZoom,
        )
    }

    private fun i32le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)
}
