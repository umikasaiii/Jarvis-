package com.simone.jarvismobile.core.context

import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fusion is where "you're home" stops being a sensor reading and becomes a fact
 * the assistant acts on. These tests are about the failure modes that make a
 * contextual assistant unbearable: announcing an arrival every time you drive
 * past, and toggling home/away all afternoon because the GPS wobbles.
 */
class PlaceFusionTest {

    private val t0 = LocalDateTime.of(2026, 8, 6, 18, 0)

    private fun fusion() = PlaceFusion(
        PlaceFusion.Config(
            enterThreshold = 0.6f,
            exitThreshold = 0.35f,
            dwell = Duration.ofMinutes(2),
            exitDebounce = Duration.ofMinutes(3),
            signalTtl = Duration.ofMinutes(10),
            cooldown = Duration.ofMinutes(1),
        ),
    )

    private fun geofence(place: String?, at: LocalDateTime, strength: Float = 1f) =
        PlaceSignal(place, SignalSource.GEOFENCE, at, strength)

    private fun wifi(place: String?, at: LocalDateTime, strength: Float = 1f) =
        PlaceSignal(place, SignalSource.WIFI, at, strength)

    // --- Arriving --------------------------------------------------------

    @Test
    fun aGeofenceAloneIsAnArrivalOnceTheDwellPasses() {
        // The direct measurement stands on its own: demanding corroboration
        // would make geofencing useless wherever there is no saved Wi-Fi.
        val f = fusion()
        assertNull(f.observe(geofence("home", t0)), "not before the dwell")
        val transition = assertNotNull(f.tick(t0.plusMinutes(3)))
        assertEquals("home", transition.to)
    }

    @Test
    fun anIndirectSignalAloneIsNotAnArrival() {
        // Wi-Fi can be a neighbour's repeater or reachable from the street.
        val f = fusion()
        assertNull(f.observe(wifi("home", t0)))
        assertNull(f.tick(t0.plusMinutes(3)))
        assertNull(f.place)
    }

    @Test
    fun activityAloneNeverEstablishesAPlace() {
        val f = fusion()
        f.observe(PlaceSignal("home", SignalSource.ACTIVITY, t0))
        assertNull(f.tick(t0.plusMinutes(5)))
        assertNull(f.place)
    }

    @Test
    fun twoIndirectSignalsTogetherDoClearTheBar() {
        val f = fusion()
        f.observe(wifi("home", t0))
        f.observe(PlaceSignal("home", SignalSource.BLUETOOTH, t0))
        val transition = assertNotNull(f.tick(t0.plusMinutes(3)))
        assertEquals("home", transition.to)
    }

    @Test
    fun twoAgreeingSourcesConfirmAfterTheDwell() {
        val f = fusion()
        f.observe(geofence("home", t0))
        // Wi-Fi corroborates: noisy-OR pushes the pair above the threshold.
        assertNull(f.observe(wifi("home", t0)), "must not fire before the dwell")
        val transition = assertNotNull(f.tick(t0.plusMinutes(3)))
        assertEquals(null, transition.from)
        assertEquals("home", transition.to)
        assertEquals("home", f.place)
        assertTrue(transition.confidence >= 0.6f, "confidence ${transition.confidence}")
    }

    @Test
    fun drivingPastDoesNotCountAsArriving() {
        // Evidence appears and disappears well inside the dwell window.
        val f = fusion()
        f.observe(geofence("home", t0))
        f.observe(wifi("home", t0))
        assertNull(f.observe(geofence(null, t0.plusSeconds(40))))
        assertNull(f.tick(t0.plusMinutes(5)))
        assertNull(f.place, "a pass-by must never become an arrival")
    }

    @Test
    fun aContradictingSourceHoldsTheArrivalBack() {
        val f = fusion()
        f.observe(geofence("home", t0))
        // Wi-Fi says somewhere else: the two disagree, so nothing is confirmed.
        f.observe(wifi("work", t0))
        assertNull(f.tick(t0.plusMinutes(5)))
        assertNull(f.place)
    }

    @Test
    fun theStrongerPlaceWinsWhenSourcesDisagree() {
        val f = fusion()
        f.observe(geofence("home", t0))
        f.observe(wifi("home", t0))
        f.observe(PlaceSignal("work", SignalSource.ACTIVITY, t0, 1f))
        val transition = assertNotNull(f.tick(t0.plusMinutes(3)))
        assertEquals("home", transition.to)
    }

    @Test
    fun aManualSignalIsTrustedImmediatelyAfterTheDwell() {
        val f = fusion()
        f.observe(PlaceSignal("home", SignalSource.MANUAL, t0))
        val transition = assertNotNull(f.tick(t0.plusMinutes(3)))
        assertEquals("home", transition.to)
    }

    // --- Not flapping ----------------------------------------------------

    @Test
    fun aWobblingGpsDoesNotProduceAStreamOfTransitions() {
        // The §3 scenario: HOME → UNKNOWN → HOME → UNKNOWN near the boundary.
        val f = fusion()
        f.observe(geofence("home", t0))
        f.observe(wifi("home", t0))
        assertNotNull(f.tick(t0.plusMinutes(3)), "arrival")

        val transitions = mutableListOf<ContextTransition>()
        var at = t0.plusMinutes(4)
        repeat(10) { i ->
            // Geofence drops out and comes back every 30 seconds; Wi-Fi holds.
            val signal = if (i % 2 == 0) geofence(null, at) else geofence("home", at)
            f.observe(signal)?.let(transitions::add)
            at = at.plusSeconds(30)
        }
        assertEquals(emptyList(), transitions, "a wobble must not generate transitions")
        assertEquals("home", f.place, "and must not lose the place either")
    }

    @Test
    fun hysteresisKeepsThePlaceWhileEvidenceIsMerelyWeakened() {
        val f = fusion()
        f.observe(geofence("home", t0))
        f.observe(wifi("home", t0))
        assertNotNull(f.tick(t0.plusMinutes(3)))

        // Geofence gone; Wi-Fi is a sustained source and keeps reporting the
        // network it is actually on. Alone it sits above the exit threshold but
        // below the entry one — precisely the band hysteresis exists to hold.
        assertNull(f.observe(geofence(null, t0.plusMinutes(4))))
        assertNull(f.observe(wifi("home", t0.plusMinutes(4))))
        assertNull(f.tick(t0.plusMinutes(8)))
        assertEquals("home", f.place, "weakened is not gone")
    }

    // --- Leaving ---------------------------------------------------------

    @Test
    fun leavingIsAnnouncedOnlyAfterTheDebounce() {
        val f = fusion()
        f.observe(geofence("home", t0))
        f.observe(wifi("home", t0))
        assertNotNull(f.tick(t0.plusMinutes(3)))

        // Both sources say "not here".
        f.observe(geofence(null, t0.plusMinutes(4)))
        assertNull(f.observe(wifi(null, t0.plusMinutes(4))), "too early to call it a departure")
        val exit = assertNotNull(f.tick(t0.plusMinutes(8)))
        assertEquals("home", exit.from)
        assertNull(exit.to)
        assertNull(f.place)
    }

    @Test
    fun aBriefDropoutIsNotADeparture() {
        val f = fusion()
        f.observe(geofence("home", t0))
        f.observe(wifi("home", t0))
        assertNotNull(f.tick(t0.plusMinutes(3)))

        f.observe(geofence(null, t0.plusMinutes(4)))
        f.observe(wifi(null, t0.plusMinutes(4)))
        // Evidence returns before the debounce elapses.
        f.observe(wifi("home", t0.plusMinutes(5)))
        f.observe(geofence("home", t0.plusMinutes(5)))
        assertNull(f.tick(t0.plusMinutes(9)))
        assertEquals("home", f.place)
    }

    @Test
    fun staleSignalsStopCountingAndTheDepartureIsEventuallySeen() {
        val f = fusion()
        f.observe(geofence("home", t0))
        f.observe(wifi("home", t0))
        assertNotNull(f.tick(t0.plusMinutes(3)))

        // No new signals at all: after the TTL the evidence expires.
        assertNull(f.tick(t0.plusMinutes(11)))
        val exit = assertNotNull(f.tick(t0.plusMinutes(20)))
        assertEquals("home", exit.from)
        assertNull(exit.to)
    }

    // --- Moving between places -------------------------------------------

    @Test
    fun movingToAnotherPlaceReportsBothEnds() {
        val f = fusion()
        f.observe(geofence("home", t0))
        f.observe(wifi("home", t0))
        assertNotNull(f.tick(t0.plusMinutes(3)))

        val at = t0.plusMinutes(30)
        f.observe(geofence("work", at))
        f.observe(wifi("work", at))
        val moved = assertNotNull(f.tick(at.plusMinutes(3)))
        assertEquals("home", moved.from, "the transition must say where we came from")
        assertEquals("work", moved.to)
    }

    @Test
    fun theSameSourceRepeatingItselfIsNotExtraEvidence() {
        val f = fusion()
        // An indirect source shouting five times is still one indirect source,
        // and must not add up to the corroboration it does not have.
        repeat(5) { i -> f.observe(wifi("home", t0.plusSeconds(i * 10L))) }
        assertNull(f.tick(t0.plusMinutes(5)), "repetition must not substitute for corroboration")
        assertNull(f.place)
    }

    @Test
    fun resetForgetsEverything() {
        val f = fusion()
        f.observe(geofence("home", t0))
        f.observe(wifi("home", t0))
        assertNotNull(f.tick(t0.plusMinutes(3)))
        f.reset()
        assertNull(f.place)
        assertEquals(0f, f.confidence(t0.plusMinutes(4)))
    }

    @Test
    fun aConfigThatWouldFlapIsRefused() {
        // exit stricter than enter means the state can never settle.
        val failed = runCatching {
            PlaceFusion.Config(enterThreshold = 0.4f, exitThreshold = 0.8f)
        }.isFailure
        assertTrue(failed)
    }
}
