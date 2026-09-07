package com.simone.jarvismobile.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 7 §1/§10-test-1 (JARVIS-06).
 * Pins the exact invariant a cache-location bug would violate: a forecast
 * cached for location A must never satisfy location B.
 */
class WeatherLocationKeyTest {

    @Test
    fun `the same coordinate always produces the same cache tag`() {
        val a = WeatherLocationKey.of(45.4642, 9.19)
        val b = WeatherLocationKey.of(45.4642, 9.19)
        assertEquals(a, b)
        assertEquals(a.asCacheTag(), b.asCacheTag())
    }

    @Test
    fun `a different coordinate never produces the same cache tag - location A cannot satisfy location B`() {
        val milan = WeatherLocationKey.of(45.4642, 9.19)
        val rome = WeatherLocationKey.of(41.9028, 12.4964)
        assertNotEquals(milan, rome)
        assertNotEquals(milan.asCacheTag(), rome.asCacheTag())
    }

    @Test
    fun `coordinates that round to the same ~1_1km cell produce the same tag`() {
        val a = WeatherLocationKey.of(45.46421, 9.19003)
        val b = WeatherLocationKey.of(45.46429, 9.18998)
        assertEquals(a.asCacheTag(), b.asCacheTag())
    }

    @Test
    fun `a place id distinguishes two saved places even at the same rounded coordinate`() {
        val home = WeatherLocationKey.of(45.4642, 9.19, placeId = "home")
        val office = WeatherLocationKey.of(45.4642, 9.19, placeId = "office")
        assertNotEquals(home.asCacheTag(), office.asCacheTag())
    }

    @Test
    fun `a place id changes the tag shape versus the same coordinate with no place chosen`() {
        val withPlace = WeatherLocationKey.of(45.4642, 9.19, placeId = "home")
        val withoutPlace = WeatherLocationKey.of(45.4642, 9.19, placeId = null)
        assertNotEquals(withPlace.asCacheTag(), withoutPlace.asCacheTag())
        assertEquals("place:home", withPlace.asCacheTag())
        assertEquals("coord:45.46,9.19", withoutPlace.asCacheTag())
    }

    @Test
    fun `a blank place id is treated the same as no place id`() {
        val blank = WeatherLocationKey.of(45.4642, 9.19, placeId = "   ")
        val none = WeatherLocationKey.of(45.4642, 9.19, placeId = null)
        assertEquals(none.asCacheTag(), blank.asCacheTag())
    }

    @Test
    fun `negative coordinates round and tag correctly`() {
        val key = WeatherLocationKey.of(-33.8688, 151.2093)
        assertEquals("coord:-33.87,151.21", key.asCacheTag())
    }
}
