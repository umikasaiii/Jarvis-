package com.simone.jarvismobile.core.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceCodecTest {

    @Test
    fun `a place survives a round trip through the file`() {
        val places = listOf(
            Place("casa", 45.464200, 9.190000, 150),
            Place("ufficio", 41.902782, 12.496366, 300),
        )
        val back = PlaceCodec.parseFile(PlaceCodec.renderFile(places))
        assertEquals(places, back)
    }

    @Test
    fun `the written form is a readable line with a dot decimal`() {
        // The point of Locale.US: a comma here would split the coordinate pair.
        assertEquals(
            "- casa @45.464200,9.190000 r150",
            PlaceCodec.render(Place("casa", 45.4642, 9.19, 150)),
        )
    }

    @Test
    fun `a place edited by hand is read back`() {
        val p = PlaceCodec.parseLine("- palestra @45.500000, 9.200000 r200")!!
        assertEquals("palestra", p.name)
        assertEquals(200, p.radiusMeters)
    }

    @Test
    fun `a missing radius falls back to the default`() {
        val p = PlaceCodec.parseLine("- casa @45.0,9.0")!!
        assertEquals(Place.DEFAULT_RADIUS_METERS, p.radiusMeters)
    }

    @Test
    fun `a line that is not a place is ignored`() {
        assertNull(PlaceCodec.parseLine("# Luoghi di JARVIS"))
        assertNull(PlaceCodec.parseLine("- casa senza coordinate"))
        assertNull(PlaceCodec.parseLine("just some text"))
    }

    @Test
    fun `an out-of-range radius is clamped rather than dropped`() {
        val p = PlaceCodec.parseLine("- casa @45.0,9.0 r99999")!!
        assertEquals(Place.MAX_RADIUS_METERS, p.radiusMeters)
    }

    @Test
    fun `the same name is stored once, last write wins on read`() {
        val back = PlaceCodec.parseFile(
            """
            # Luoghi di JARVIS

            - casa @45.0,9.0 r150
            - Casa @46.0,10.0 r200
            """.trimIndent(),
        )
        assertEquals(1, back.size)
        assertTrue(back.first().name == "casa")
    }
}
