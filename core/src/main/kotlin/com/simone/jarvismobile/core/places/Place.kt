package com.simone.jarvismobile.core.places

/**
 * A place the user has named, so an automation can say "arrivo a casa" instead
 * of carrying a pair of coordinates around.
 *
 * The name is the join key between a rule and its geofence: an [ArrivedAt]
 * automation stores only the name, and the coordinates live here. Matching is
 * done on [key] — a trimmed, lower-cased, single-spaced form — so "Casa",
 * "casa" and "  casa " are the same place, but the original [name] is kept for
 * display.
 *
 * Coordinates are the only sensitive thing JARVIS stores about a place, and they
 * are put where every other durable fact goes: a human-readable Markdown line
 * the user can read, edit or delete in Obsidian (`JARVIS/Luoghi.md`).
 */
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude in -180.0..180.0) { "longitude out of range" }
        require(radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS) { "radius out of range" }
    }

    /** The normalised name used to match a rule to its place. */
    val key: String get() = normalize(name)

    companion object {
        const val DEFAULT_RADIUS_METERS = 150
        const val MIN_RADIUS_METERS = 50
        const val MAX_RADIUS_METERS = 5000

        fun normalize(name: String): String =
            name.trim().lowercase().replace(Regex("\\s+"), " ")

        /** True when [name] can be stored: a place with no name cannot be matched. */
        fun isValidName(name: String): Boolean = normalize(name).isNotEmpty()

        fun clampRadius(meters: Int): Int = meters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
    }
}
