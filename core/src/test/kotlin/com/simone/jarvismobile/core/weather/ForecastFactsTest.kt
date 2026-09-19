package com.simone.jarvismobile.core.weather

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForecastFactsTest {

    private val fetchedAt = Instant.parse("2026-09-20T18:00:00Z")

    // --- W01: date matching, never positional -------------------------------

    @Test fun indexOfDate_findsTheRealMatchEvenWhenReordered() {
        val dates = listOf("2026-09-21", "2026-09-19", "2026-09-20")
        assertEquals(2, ForecastDateMatcher.indexOfDate(dates, LocalDate.of(2026, 9, 20)))
        assertEquals(0, ForecastDateMatcher.indexOfDate(dates, LocalDate.of(2026, 9, 21)))
    }

    @Test fun indexOfDate_missingDateReturnsNull_neverAGuessedPosition() {
        val dates = listOf("2026-09-19", "2026-09-21")
        assertNull(ForecastDateMatcher.indexOfDate(dates, LocalDate.of(2026, 9, 20)))
    }

    @Test fun indexOfDate_nullOrEmptyArrayReturnsNull() {
        assertNull(ForecastDateMatcher.indexOfDate(null, LocalDate.of(2026, 9, 20)))
        assertNull(ForecastDateMatcher.indexOfDate(emptyList(), LocalDate.of(2026, 9, 20)))
    }

    @Test fun indexOfDate_nullElementsInArrayNeverCrash() {
        val dates = listOf(null, "2026-09-20", null)
        assertEquals(1, ForecastDateMatcher.indexOfDate(dates, LocalDate.of(2026, 9, 20)))
    }

    @Test fun fromDaily_mismatchedArrayLengthsNeverCrash_missingFieldsStayNull() {
        val target = LocalDate.of(2026, 9, 20)
        val facts = ForecastFactsBuilder.fromDaily(
            targetDate = target,
            providerTimezone = "Europe/Rome",
            locationRevision = "coord:41.90,12.50",
            fetchedAt = fetchedAt,
            times = listOf("2026-09-19", "2026-09-20", "2026-09-21"),
            weatherCodes = listOf(1, 61), // shorter than times — index 1 exists
            precipitationSum = listOf(0.0), // shorter still — index 1 out of bounds
            rainSum = null, // field never requested
            showersSum = null,
            snowfallSum = null,
            precipitationProbabilityMax = listOf(10.0, 80.0, 20.0),
            precipitationHours = listOf(0.0, 3.0, 1.0),
        )
        requireNotNull(facts)
        assertEquals(61, facts.rawWeatherCode)
        assertNull(facts.precipitationSumMm) // out of bounds -> null, never a crash or a zero
        assertNull(facts.rainSumMm) // field never requested -> null
        assertEquals(80.0, facts.precipitationProbabilityMaxPercent)
    }

    @Test fun fromDaily_missingTargetDateReturnsNull() {
        val facts = ForecastFactsBuilder.fromDaily(
            targetDate = LocalDate.of(2026, 9, 22),
            providerTimezone = null,
            locationRevision = "coord:41.90,12.50",
            fetchedAt = fetchedAt,
            times = listOf("2026-09-19", "2026-09-20"),
            weatherCodes = listOf(1, 61),
            precipitationSum = listOf(0.0, 1.0),
            rainSum = null,
            showersSum = null,
            snowfallSum = null,
            precipitationProbabilityMax = null,
            precipitationHours = null,
        )
        assertNull(facts)
    }

    @Test fun fromDaily_realZeroIsPreserved_neverCollapsedFromNull() {
        val facts = ForecastFactsBuilder.fromDaily(
            targetDate = LocalDate.of(2026, 9, 20),
            providerTimezone = "Europe/Rome",
            locationRevision = "coord:41.90,12.50",
            fetchedAt = fetchedAt,
            times = listOf("2026-09-20"),
            weatherCodes = listOf(0),
            precipitationSum = listOf(0.0),
            rainSum = listOf(0.0),
            showersSum = null,
            snowfallSum = null,
            precipitationProbabilityMax = listOf(0.0),
            precipitationHours = listOf(0.0),
        )
        requireNotNull(facts)
        assertEquals(0.0, facts.precipitationSumMm)
        assertEquals(0.0, facts.rainSumMm)
        assertEquals(0.0, facts.precipitationProbabilityMaxPercent)
        assertNull(facts.showersSumMm) // never requested -> stays null, not 0.0
    }

    // --- W05: raw code / precipitation kind classification -----------------

    @Test fun wmoPrecipitationKind_snowNeverClassifiesAsRain() {
        for (code in listOf(71, 73, 75, 77, 85, 86)) {
            assertEquals(PrecipitationKind.SNOW, WmoPrecipitationKind.classify(code), "code=$code")
        }
    }

    @Test fun wmoPrecipitationKind_mixedIsDistinctFromPlainRain() {
        for (code in listOf(56, 57, 66, 67, 96, 99)) {
            assertEquals(PrecipitationKind.MIXED, WmoPrecipitationKind.classify(code), "code=$code")
        }
    }

    @Test fun wmoPrecipitationKind_genuineRainCodes() {
        for (code in listOf(51, 53, 55, 61, 63, 65, 80, 81, 82, 95)) {
            assertEquals(PrecipitationKind.RAIN, WmoPrecipitationKind.classify(code), "code=$code")
        }
    }

    @Test fun wmoPrecipitationKind_nullIsUnknownNeverGuessed() {
        assertEquals(PrecipitationKind.UNKNOWN, WmoPrecipitationKind.classify(null))
    }

    @Test fun wmoPrecipitationKind_undocumentedCodeIsUnknown() {
        assertEquals(PrecipitationKind.UNKNOWN, WmoPrecipitationKind.classify(12345))
    }

    // --- facts hash (§35 stable, §11/§23 evidence identity) -----------------

    @Test fun factsHash_isStableForIdenticalFacts() {
        val a = sampleFacts()
        val b = sampleFacts()
        assertEquals(ForecastFactsHash.of(a), ForecastFactsHash.of(b))
    }

    @Test fun factsHash_changesWhenAnyFieldChanges() {
        val a = sampleFacts()
        val b = sampleFacts().copy(precipitationSumMm = 5.0)
        assertNotEquals(ForecastFactsHash.of(a), ForecastFactsHash.of(b))
    }

    @Test fun factsHash_isHexEncodedSha256Length() {
        assertTrue(ForecastFactsHash.of(sampleFacts()).length == 64)
    }

    private fun sampleFacts() = ForecastFacts(
        targetDate = LocalDate.of(2026, 9, 20),
        providerTimezone = "Europe/Rome",
        locationRevision = "coord:41.90,12.50",
        fetchedAt = fetchedAt,
        rawWeatherCode = 61,
        category = WeatherCategory.RAIN,
        precipitationSumMm = 2.0,
        rainSumMm = 2.0,
        showersSumMm = null,
        snowfallSumCm = null,
        precipitationProbabilityMaxPercent = 80.0,
        precipitationHours = 3.0,
    )
}
