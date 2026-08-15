package com.simone.jarvismobile.core.places

import java.util.Locale

/**
 * Reads and writes `JARVIS/Luoghi.md`.
 *
 * A place reads `- casa @45.464200,9.190000 r150`: a name, its coordinates and
 * the radius in metres that counts as "there". The file is the source of truth,
 * so it stays a plain, editable list — deleting a line deletes a place, and the
 * geofence that watched it goes with it on the next sync.
 *
 * Coordinates are written with six decimals (~0.1 m), which is far finer than
 * any geofence needs; the radius, not the precision of the point, is what
 * decides when a rule fires.
 */
object PlaceCodec {

    const val FILE_HEADER = "# Luoghi di JARVIS"

    private val LINE = Regex(
        """^\s*-\s*(.+?)\s+@(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)(?:\s+r(\d{1,5}))?\s*$""",
    )

    fun parseFile(text: String): List<Place> =
        text.lineSequence().mapNotNull { parseLine(it) }.distinctBy { it.key }.toList()

    fun renderFile(places: List<Place>): String = buildString {
        append(FILE_HEADER).append("\n\n")
        places.forEach { append(render(it)).append('\n') }
    }

    fun parseLine(line: String): Place? {
        val m = LINE.find(line) ?: return null
        val name = m.groupValues[1].trim()
        if (name.isEmpty() || name.startsWith("#")) return null
        val lat = m.groupValues[2].toDoubleOrNull() ?: return null
        val lon = m.groupValues[3].toDoubleOrNull() ?: return null
        val radius = m.groupValues[4].toIntOrNull() ?: Place.DEFAULT_RADIUS_METERS
        // A malformed coordinate should skip the line, not crash the whole file.
        return runCatching {
            Place(name = name, latitude = lat, longitude = lon, radiusMeters = Place.clampRadius(radius))
        }.getOrNull()
    }

    fun render(place: Place): String =
        // Locale.US keeps the decimal point a dot; an Italian locale would emit a
        // comma and split the coordinate pair when the file is read back.
        String.format(
            Locale.US,
            "- %s @%.6f,%.6f r%d",
            place.name,
            place.latitude,
            place.longitude,
            place.radiusMeters,
        )
}
