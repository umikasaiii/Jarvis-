package com.simone.jarvismobile.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GpxReplayTest {

    private val withTimes = """
        <?xml version="1.0"?>
        <gpx><trk><trkseg>
            <trkpt lat="41.9000" lon="12.5000"><time>2026-01-01T10:00:00Z</time></trkpt>
            <trkpt lat="41.9010" lon="12.5000"><time>2026-01-01T10:00:10Z</time></trkpt>
            <trkpt lat="41.9020" lon="12.5000"><time>2026-01-01T10:00:20Z</time></trkpt>
        </trkseg></trk></gpx>
    """.trimIndent()

    private val withoutTimes = """
        <gpx><trk><trkseg>
            <trkpt lat="41.9000" lon="12.5000"></trkpt>
            <trkpt lat="41.9010" lon="12.5000"></trkpt>
        </trkseg></trk></gpx>
    """.trimIndent()

    @Test fun parsesRealTimestampsAsElapsedOffsets() {
        val points = GpxParser.parse(withTimes)
        assertEquals(3, points.size)
        assertEquals(0L, points[0].elapsedMs)
        assertEquals(10_000L, points[1].elapsedMs)
        assertEquals(20_000L, points[2].elapsedMs)
    }

    @Test fun missingTimesFallBackToFixedInterval() {
        val points = GpxParser.parse(withoutTimes, fallbackIntervalMs = 2_000L)
        assertEquals(2, points.size)
        assertEquals(0L, points[0].elapsedMs)
        assertEquals(2_000L, points[1].elapsedMs)
    }

    @Test fun malformedXmlReturnsEmptyNotACrash() {
        assertTrue(GpxParser.parse("not xml at all <<<").isEmpty())
    }

    @Test fun emptyTrackReturnsEmpty() {
        assertTrue(GpxParser.parse("<gpx></gpx>").isEmpty())
    }

    @Test fun replayInterpolatesBetweenPoints() {
        val route = GpxReplayRoute(GpxParser.parse(withTimes))
        val mid = route.fixAt(5_000L)
        assertTrue(mid.location.lat in 41.9000..41.9010, "expected interpolated lat, was ${mid.location.lat}")
        assertTrue(mid.speedMps != null && mid.speedMps!! > 0f)
        assertTrue(mid.bearingDegrees != null)
    }

    @Test fun replayClampsPastDuration() {
        val route = GpxReplayRoute(GpxParser.parse(withTimes))
        val end = route.fixAt(999_999L)
        assertEquals(41.9020, end.location.lat, 1e-6)
    }

    @Test fun replayClampsBeforeStart() {
        val route = GpxReplayRoute(GpxParser.parse(withTimes))
        val start = route.fixAt(-500L)
        assertEquals(41.9000, start.location.lat, 1e-6)
    }

    @Test fun singlePointTrackRejected() {
        assertFailsWith<IllegalArgumentException> {
            GpxReplayRoute(listOf(GpxPoint(LatLng(41.9, 12.5), 0L)))
        }
    }
}
