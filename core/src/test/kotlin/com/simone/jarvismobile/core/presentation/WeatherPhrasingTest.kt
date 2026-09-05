package com.simone.jarvismobile.core.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * § FASE 2A.8 RELEASE GATE I — pins the two hard constraints: every template
 * index produces a DIFFERENT sentence for the same grounded fields (real
 * variation, not decoration on the same string), and a null field is NEVER
 * rendered as if it were a real value (nothing invented).
 */
class WeatherPhrasingTest {

    private fun render(index: Int) = WeatherPhrasing.render(
        templateIndex = index,
        dayLabel = "oggi",
        category = "Sereno",
        currentTempC = 22.0,
        tempMaxC = null,
        tempMinC = null,
        windKmh = 10.0,
    )

    @Test
    fun `every template index in range produces a distinct sentence for the same facts`() {
        val sentences = (0 until WeatherPhrasing.TEMPLATE_COUNT).map(::render)
        assertEquals(sentences.size, sentences.toSet().size, "expected $sentences to be pairwise distinct")
    }

    @Test
    fun `an index outside the range wraps around instead of crashing`() {
        assertEquals(render(0), render(WeatherPhrasing.TEMPLATE_COUNT))
        assertEquals(render(1), render(-WeatherPhrasing.TEMPLATE_COUNT + 1))
    }

    @Test
    fun `a null wind is never rendered as a number`() {
        for (i in 0 until WeatherPhrasing.TEMPLATE_COUNT) {
            val sentence = WeatherPhrasing.render(i, "oggi", "Sereno", 22.0, null, null, windKmh = null)
            assertTrue(!sentence.contains("km/h"), "template $i invented wind: $sentence")
        }
    }

    @Test
    fun `a null category is never rendered as a word`() {
        for (i in 0 until WeatherPhrasing.TEMPLATE_COUNT) {
            val sentence = WeatherPhrasing.render(i, "domani", null, null, 20.0, 12.0, windKmh = null)
            assertTrue(!sentence.contains("Cielo null") && !sentence.contains(": null"), "template $i invented category: $sentence")
        }
    }

    @Test
    fun `current temp is preferred over max-min when both are somehow present, never both stated as if different numbers`() {
        val sentence = WeatherPhrasing.render(0, "oggi", "Sereno", currentTempC = 22.0, tempMaxC = 25.0, tempMinC = 18.0, windKmh = null)
        assertTrue(sentence.contains("22"))
        assertTrue(!sentence.contains("25") && !sentence.contains("18"))
    }

    @Test
    fun `max and min both present without a current reading render as a range, not a single guessed number`() {
        val sentence = WeatherPhrasing.render(0, "tra 5 giorni", "Nuvoloso", currentTempC = null, tempMaxC = 25.0, tempMinC = 18.0, windKmh = null)
        assertTrue(sentence.contains("18") && sentence.contains("25"))
    }

    @Test
    fun `all fields null renders an honest no-data sentence, never an empty or crashing one`() {
        val sentence = WeatherPhrasing.render(0, "oggi", null, null, null, null, null)
        assertEquals("Oggi: nessun dato meteo disponibile.", sentence)
    }

    @Test
    fun `day label is capitalized regardless of template`() {
        for (i in 0 until WeatherPhrasing.TEMPLATE_COUNT) {
            val sentence = WeatherPhrasing.render(i, "domani", "Sereno", 20.0, null, null, null)
            assertTrue(sentence.startsWith("Domani") || sentence.contains("Domani"), "template $i lost capitalization: $sentence")
        }
    }

    @Test
    fun `distinct calls with different facts are never accidentally equal`() {
        val a = render(0)
        val b = WeatherPhrasing.render(0, "oggi", "Piovoso", 10.0, null, null, 30.0)
        assertNotEquals(a, b)
    }
}
