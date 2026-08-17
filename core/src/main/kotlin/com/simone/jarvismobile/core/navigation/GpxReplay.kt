package com.simone.jarvismobile.core.navigation

import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/** One trackpoint from a GPX file, with its elapsed time since the first point. */
data class GpxPoint(val location: LatLng, val elapsedMs: Long)

/**
 * Parses GPX 1.1 `<trkpt>` sequences for debug route replay (spec §28) — no new
 * dependency, DOM parsing is part of the standard library on both a plain JVM
 * (tests) and Android. A point's `<time>` (ISO-8601) becomes its elapsed offset
 * from the first point; a track with no `<time>` elements falls back to a fixed
 * interval between points so it still replays at a sane pace instead of failing.
 */
object GpxParser {

    fun parse(xml: String, fallbackIntervalMs: Long = 1_000L): List<GpxPoint> {
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // Debug fixtures are user-supplied local files, never fetched over
                // the network — disable DOCTYPE/external-entity resolution anyway.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return emptyList()

        val trkpts = doc.getElementsByTagName("trkpt")
        val raw = ArrayList<Pair<LatLng, Instant?>>(trkpts.length)
        for (i in 0 until trkpts.length) {
            val el = trkpts.item(i) as? org.w3c.dom.Element ?: continue
            val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
            val timeText = el.getElementsByTagName("time").item(0)?.textContent
            val time = timeText?.let { runCatching { Instant.parse(it.trim()) }.getOrNull() }
            raw += LatLng(lat, lon) to time
        }
        if (raw.isEmpty()) return emptyList()

        val hasAllTimes = raw.all { it.second != null }
        return if (hasAllTimes) {
            val t0 = raw.first().second!!.toEpochMilli()
            raw.map { (loc, t) -> GpxPoint(loc, t!!.toEpochMilli() - t0) }
        } else {
            raw.mapIndexed { i, (loc, _) -> GpxPoint(loc, i * fallbackIntervalMs) }
        }
    }
}

/**
 * Replays a parsed GPX track as [GpsFix]es keyed by elapsed time, interpolating
 * position/bearing/speed between the two surrounding points — the same shape
 * [SimulatedGpsRoute] already provides for the synthetic loop, so the Android
 * location provider can use either behind one flow (spec §28, debug-only:
 * never compiled into a meaningful state in release — see `DebugGpsSimulator`).
 */
class GpxReplayRoute(private val points: List<GpxPoint>) {
    init { require(points.size >= 2) { "GPX replay needs at least two points" } }

    val durationMs: Long get() = points.last().elapsedMs

    fun fixAt(elapsedMs: Long): GpsFix {
        val clamped = elapsedMs.coerceIn(0L, durationMs)
        val i = points.indexOfLast { it.elapsedMs <= clamped }.coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        val span = (b.elapsedMs - a.elapsedMs).coerceAtLeast(1L)
        val t = ((clamped - a.elapsedMs).toDouble() / span).coerceIn(0.0, 1.0)
        val loc = LatLng(
            a.location.lat + (b.location.lat - a.location.lat) * t,
            a.location.lon + (b.location.lon - a.location.lon) * t,
        )
        val bearing = Geo.bearingDegrees(a.location, b.location).toFloat()
        val speed = (Geo.distanceMeters(a.location, b.location) / (span / 1000.0)).toFloat()
        return GpsFix(loc, accuracyMeters = 5f, speedMps = speed, bearingDegrees = bearing, timestampMs = elapsedMs)
    }
}
