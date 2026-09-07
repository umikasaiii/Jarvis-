package com.simone.jarvismobile.core.weather

import java.util.Locale

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 7 §1 (JARVIS-06). A stable,
 * privacy-safe location identity for weather cache/evidence — the exact gap
 * found in the audit: [com.simone.jarvismobile.weather.WeatherOutlookCache]'s
 * persisted JSON carried a forecast's fields and a cache timestamp, but NO
 * identifier of which location the forecast was actually for. Since the
 * user's chosen weather place (or the GPS/network fallback fix) can change
 * between two calls, a forecast fetched for location A could be silently
 * read back and presented as the forecast for location B — the exact
 * violation this key exists to make structurally impossible: two
 * [WeatherLocationKey] instances are equal (and produce the same
 * [asCacheTag]) only when they represent the same place.
 *
 * [roundedLatitude]/[roundedLongitude] use the SAME ~1.1km rounding applied
 * to every outgoing request ([roundWeatherCoordinate], reused by
 * [com.simone.jarvismobile.weather.OpenMeteoWeatherSource]'s own request
 * builder — one formula, never two to keep in sync) — never the exact fix.
 * [placeId] additionally distinguishes two different SAVED places that
 * happen to round to the same coordinate (unlikely, but a stable id costs
 * nothing to include and survives a place rename, unlike its name).
 */
data class WeatherLocationKey(
    val roundedLatitude: String,
    val roundedLongitude: String,
    val placeId: String? = null,
) {
    /** A single stable string safe to persist in a cache entry or expose as evidence — never the precise coordinate. */
    fun asCacheTag(): String = placeId?.let { "place:$it" } ?: "coord:$roundedLatitude,$roundedLongitude"

    companion object {
        fun of(latitude: Double, longitude: Double, placeId: String? = null): WeatherLocationKey =
            WeatherLocationKey(roundWeatherCoordinate(latitude), roundWeatherCoordinate(longitude), placeId?.takeIf { it.isNotBlank() })
    }
}

/**
 * ~1.1km precision (2 decimals) — enough for a local forecast, never the
 * user's exact address. The one shared rounding rule for every outgoing
 * weather request AND every cache/evidence location tag ([WeatherLocationKey]) —
 * never two separately-maintained roundings that could silently drift apart.
 */
fun roundWeatherCoordinate(coordinate: Double): String = String.format(Locale.US, "%.2f", coordinate)
