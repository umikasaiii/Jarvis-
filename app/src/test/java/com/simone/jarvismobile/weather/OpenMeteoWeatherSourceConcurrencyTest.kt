package com.simone.jarvismobile.weather

import com.simone.jarvismobile.core.weather.WeatherFailureReason
import com.simone.jarvismobile.core.weather.WeatherRequestOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D.1 §7 (W08). A REAL executed concurrency test against a local
 * `MockWebServer` (§7: "deterministic fakes/mock transport") — never a
 * reimplemented fake of [OpenMeteoWeatherSource]'s own request/parse code,
 * exercising the actual production class with its `baseUrl` pointed at the
 * mock server (see that field's own doc comment).
 *
 * Scenario, exactly as specified: one request succeeds (A, a dated daily
 * forecast for a distinct location), one fails (B, a dated daily forecast
 * for a DIFFERENT location, HTTP 500), one completes later (C, an hourly
 * evidence request for A's own location/date, artificially delayed) — all
 * three in flight concurrently via [kotlinx.coroutines.async].
 */
class OpenMeteoWeatherSourceConcurrencyTest {

    private lateinit var server: MockWebServer
    private lateinit var source: OpenMeteoWeatherSource

    private val targetDate: LocalDate = LocalDate.of(2026, 9, 20)

    private val datedResponseA = """
        {"timezone":"Europe/Rome","daily":{
          "time":["2026-09-19","2026-09-20","2026-09-21"],
          "weathercode":[1,61,2],
          "precipitation_sum":[0.0,3.0,0.0],
          "rain_sum":[0.0,3.0,0.0],
          "showers_sum":[null,null,null],
          "snowfall_sum":[null,null,null],
          "precipitation_probability_max":[10.0,85.0,5.0],
          "precipitation_hours":[0.0,3.0,0.0]
        }}
    """.trimIndent()

    private val hourlyResponseA = """
        {"hourly":{
          "time":["2026-09-20T00:00","2026-09-20T15:00","2026-09-20T23:00"],
          "weathercode":[61,61,61],
          "precipitation_probability":[10.0,85.0,5.0],
          "rain":[0.0,1.2,0.0],
          "showers":[null,null,null],
          "precipitation":[0.0,1.2,0.0]
        }}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    // Request C — hourly evidence for A's location, delayed
                    // so it genuinely "completes later" than A/B.
                    path.contains("hourly=") -> {
                        Thread.sleep(HOURLY_DELAY_MS)
                        MockResponse().setResponseCode(200).setBody(hourlyResponseA)
                    }
                    // Request B — a DIFFERENT location, fails.
                    path.contains("latitude=45.00") -> MockResponse().setResponseCode(500)
                    // Request A — succeeds immediately.
                    path.contains("latitude=41.90") -> MockResponse().setResponseCode(200).setBody(datedResponseA)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = OpenMeteoWeatherSource().apply { baseUrl = server.url("").toString().removeSuffix("/") }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun concurrentRequests_oneSucceedsOneFailsOneCompletesLater_noCrossContamination() = runTest {
        val requestA = async { source.fetchDatedDailyForecast(41.90, 12.50, targetDate, "loc-A") }
        val requestB = async { source.fetchDatedDailyForecast(45.00, 9.00, targetDate, "loc-B") }
        val requestC = async { source.fetchAlignedHourlyEvidence(41.90, 12.50, targetDate) }
        // Each .await() separately (rather than awaitAll, whose common-type
        // inference would collapse the two different result types) — all
        // three were already started above, so this still awaits them
        // concurrently in flight, only reading the results sequentially.
        val outcomeA = requestA.await()
        val outcomeB = requestB.await()
        val outcomeC = requestC.await()

        // --- request identity is preserved: A's accepted facts correspond
        // to A's own request/location, never B's or C's. ---
        val successA = outcomeA as? WeatherRequestOutcome.Success ?: error("expected A to succeed, got $outcomeA")
        assertEquals("loc-A", successA.value.locationRevision)
        assertEquals(targetDate, successA.value.targetDate)
        assertEquals(61, successA.value.rawWeatherCode)
        assertEquals(3.0, successA.value.rainSumMm)

        // --- B's failure is its own, never bleeding into A or C. ---
        val failureB = outcomeB as? WeatherRequestOutcome.Failure ?: error("expected B to fail, got $outcomeB")
        assertEquals(WeatherFailureReason.NETWORK, failureB.reason)

        // --- C completes later but still resolves correctly, unaffected
        // by B's concurrent failure. ---
        @Suppress("UNCHECKED_CAST")
        val successC = outcomeC as? WeatherRequestOutcome.Success<List<com.simone.jarvismobile.core.weather.HourlyPrecipitationEvidence>>
            ?: error("expected C to succeed, got $outcomeC")
        assertTrue(successC.value.isNotEmpty())
        assertTrue(successC.value.all { it.date == targetDate })

        // --- no shared lastFetchError-style contamination: these two
        // request-scoped methods never touch OpenMeteoWeatherSource's
        // legacy @Volatile lastErrorType field (used only by the four
        // pre-existing fetch methods) — B's failure must not leak there. ---
        assertNull(source.lastFetchErrorType())
    }

    @Test
    fun concurrentDatedRequests_differentLocations_eachGetsItsOwnOutcome_repeatable() = runTest {
        // Same scenario run twice back-to-back on the SAME source instance —
        // proves no residual per-instance state from the first round leaks
        // into the second (request-scoped by construction, not merely by
        // accident of ordering).
        repeat(2) {
            val a = async { source.fetchDatedDailyForecast(41.90, 12.50, targetDate, "loc-A") }
            val b = async { source.fetchDatedDailyForecast(45.00, 9.00, targetDate, "loc-B") }
            val outcomeA = a.await()
            val outcomeB = b.await()
            assertTrue(outcomeA is WeatherRequestOutcome.Success<*>)
            assertTrue(outcomeB is WeatherRequestOutcome.Failure)
        }
    }

    private companion object {
        const val HOURLY_DELAY_MS = 150L
    }
}
