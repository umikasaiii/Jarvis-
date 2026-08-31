package com.simone.jarvismobile.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindDirectionTest {

    @Test
    fun `cardinal points`() {
        assertEquals("N", WindDirection.label(0.0))
        assertEquals("E", WindDirection.label(90.0))
        assertEquals("S", WindDirection.label(180.0))
        assertEquals("W", WindDirection.label(270.0))
    }

    @Test
    fun `intercardinal and secondary points`() {
        assertEquals("NNE", WindDirection.label(22.5))
        assertEquals("NE", WindDirection.label(45.0))
        assertEquals("SE", WindDirection.label(135.0))
        assertEquals("NW", WindDirection.label(315.0))
    }

    @Test
    fun `wraps around 360`() {
        assertEquals("N", WindDirection.label(360.0))
        assertEquals("N", WindDirection.label(359.9))
        assertEquals("N", WindDirection.label(-0.1))
    }

    @Test
    fun `null and NaN stay unknown`() {
        assertNull(WindDirection.label(null))
        assertNull(WindDirection.label(Double.NaN))
    }
}
