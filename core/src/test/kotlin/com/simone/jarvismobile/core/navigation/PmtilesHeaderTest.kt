package com.simone.jarvismobile.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmtilesHeaderTest {

    private fun putI32le(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
        b[off + 2] = ((v shr 16) and 0xff).toByte()
        b[off + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun header(
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double,
        minZoom: Int = 5, maxZoom: Int = 14, version: Int = 3, magic: String = "PMTiles",
    ): ByteArray {
        val b = ByteArray(PmtilesHeaderParser.HEADER_SIZE)
        for (i in magic.indices) b[i] = magic[i].code.toByte()
        b[7] = version.toByte()
        b[100] = minZoom.toByte()
        b[101] = maxZoom.toByte()
        putI32le(b, 102, (minLon * 1e7).toInt())
        putI32le(b, 106, (minLat * 1e7).toInt())
        putI32le(b, 110, (maxLon * 1e7).toInt())
        putI32le(b, 114, (maxLat * 1e7).toInt())
        putI32le(b, 119, ((minLon + maxLon) / 2 * 1e7).toInt())
        putI32le(b, 123, ((minLat + maxLat) / 2 * 1e7).toInt())
        return b
    }

    @Test fun parsesLazioBounds() {
        // Lazio-ish bounding box.
        val h = PmtilesHeaderParser.parse(header(11.45, 41.0, 13.9, 42.7))
        requireNotNull(h)
        assertTrue(h.bounds.contains(LatLng(41.9, 12.5)), "Rome should be inside")
        assertEquals(5, h.minZoom)
        assertEquals(14, h.maxZoom)
        assertTrue(h.center.lat in 41.0..42.7)
    }

    @Test fun rejectsWrongMagic() {
        assertNull(PmtilesHeaderParser.parse(header(11.4, 41.0, 13.9, 42.7, magic = "NOTPMTi")))
    }

    @Test fun rejectsWrongVersion() {
        assertNull(PmtilesHeaderParser.parse(header(11.4, 41.0, 13.9, 42.7, version = 2)))
    }

    @Test fun rejectsTooShort() {
        assertNull(PmtilesHeaderParser.parse(ByteArray(10)))
    }

    @Test fun rejectsCorruptBounds() {
        // min > max latitude → corrupt.
        assertNull(PmtilesHeaderParser.parse(header(11.4, 43.0, 13.9, 41.0)))
    }
}
