package com.simone.jarvismobile.core.weather

/**
 * Converts a wind-direction bearing (Open-Meteo's `winddirection_10m_*`, 0-360°
 * where 0/360 is true north) to the 16-point compass label the Ares theme's
 * weather panel shows ("NNE", "SE", …) instead of a raw degree number, matching
 * the reference layout the user provided.
 */
object WindDirection {
    private val LABELS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /** Null in, null out — an unknown bearing stays unknown, never a guess. */
    fun label(degrees: Double?): String? {
        if (degrees == null || degrees.isNaN()) return null
        val normalized = ((degrees % 360) + 360) % 360
        val index = ((normalized / 22.5) + 0.5).toInt() % 16
        return LABELS[index]
    }
}
