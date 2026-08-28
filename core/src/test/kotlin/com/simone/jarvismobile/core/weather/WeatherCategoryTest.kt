package com.simone.jarvismobile.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeatherCategoryTest {

    @Test fun `a null code stays unknown`() {
        assertNull(WeatherCategory.fromWmoCode(null))
    }

    @Test fun `an undocumented code stays unknown rather than guessed`() {
        assertNull(WeatherCategory.fromWmoCode(-1))
        assertNull(WeatherCategory.fromWmoCode(12345))
    }

    @Test fun `clear sky`() {
        assertEquals(WeatherCategory.CLEAR, WeatherCategory.fromWmoCode(0))
    }

    @Test fun `partly cloudy`() {
        assertEquals(WeatherCategory.PARTLY_CLOUDY, WeatherCategory.fromWmoCode(1))
        assertEquals(WeatherCategory.PARTLY_CLOUDY, WeatherCategory.fromWmoCode(2))
    }

    @Test fun `overcast and fog both read as cloudy`() {
        assertEquals(WeatherCategory.CLOUDY, WeatherCategory.fromWmoCode(3))
        assertEquals(WeatherCategory.CLOUDY, WeatherCategory.fromWmoCode(45))
        assertEquals(WeatherCategory.CLOUDY, WeatherCategory.fromWmoCode(48))
    }

    @Test fun `drizzle, rain and showers all read as rain`() {
        for (code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)) {
            assertEquals(WeatherCategory.RAIN, WeatherCategory.fromWmoCode(code), "code $code")
        }
    }

    @Test fun `snow folds into rain, no dedicated bucket was requested`() {
        for (code in listOf(71, 73, 75, 77, 85, 86)) {
            assertEquals(WeatherCategory.RAIN, WeatherCategory.fromWmoCode(code), "code $code")
        }
    }

    @Test fun `thunderstorm codes are their own category`() {
        assertEquals(WeatherCategory.THUNDERSTORM, WeatherCategory.fromWmoCode(95))
        assertEquals(WeatherCategory.THUNDERSTORM, WeatherCategory.fromWmoCode(96))
        assertEquals(WeatherCategory.THUNDERSTORM, WeatherCategory.fromWmoCode(99))
    }
}
