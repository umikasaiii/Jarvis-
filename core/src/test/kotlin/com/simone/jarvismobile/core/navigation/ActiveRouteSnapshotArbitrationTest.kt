package com.simone.jarvismobile.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers JARVIS Implementation Master Plan PASSAGGIO 9.1 (§13) — proves the
 * actual repository arbitration SHAPE, not just [RouteGenerationGate] in
 * isolation: capture one [ActiveRouteSnapshot] tagged with a generation,
 * compute derived values from it, then gate the WHOLE resulting mutation
 * group behind [RouteGenerationGate.publishIfCurrent] for that snapshot's own
 * generation — exactly the pattern [com.simone.jarvismobile.navigation.NavigationRepository]'s
 * `onFix()`/`publishRoute()`/`publishRecalculatedRoute()`/`publishRerouteFailure()`
 * now use. `RouteGenerationGateTest` alone proves the underlying primitive is
 * correct; this file proves that composing it with a real, atomically-published
 * snapshot — the specific fix this correction gate makes — closes RACE 1/2/3
 * as described by the passage, using real `:core` domain types (`Route`,
 * `MapMatcher`, `RouteProgressCalculator`, `GpsFix`), not stand-ins.
 */
class ActiveRouteSnapshotArbitrationTest {

    // --- shared fixtures ----------------------------------------------------

    private fun straightRoute(destination: LatLng = LatLng(1.0, 0.0)): Route {
        // Geometry actually spans from (0,0) to [destination] — a fix that
        // sits near one route's line must genuinely NOT sit near another
        // route's differently-anchored line, so this fixture can tell a
        // route-A match from a route-B match by real distance, not just by
        // object identity.
        val geometry = (0..4).map { i ->
            val t = i / 4.0
            LatLng(destination.lat * t, destination.lon * t)
        }
        val maneuvers = listOf(
            Maneuver(ManeuverType.DEPART, geometry[0], 0),
            Maneuver(ManeuverType.ARRIVE, geometry.last(), geometry.lastIndex),
        )
        return Route.fromGeometry(geometry, maneuvers, destination)
    }

    private fun snapshotFor(generation: Long, route: Route = straightRoute()): ActiveRouteSnapshot {
        val matcher = MapMatcher(route)
        return ActiveRouteSnapshot(generation, route, matcher, RouteProgressCalculator(route, matcher))
    }

    private fun fixNear(point: LatLng, timestampMs: Long = 0L): GpsFix =
        GpsFix(location = point, accuracyMeters = 5f, speedMps = 5f, bearingDegrees = 0f, timestampMs = timestampMs)

    /**
     * A minimal stand-in for the repository's own mutable fields
     * (`_progress`/`_message`/`activeSnapshot`) — real `MutableStateFlow`s are
     * Android/`app`-module-adjacent in spirit but plain `var`s here are
     * enough to prove the arbitration shape without needing coroutines-test
     * infrastructure the gate itself doesn't depend on.
     */
    private class FakeRepositoryState {
        var progress: NavigationProgress? = null
        var message: String? = null
        var activeSnapshot: ActiveRouteSnapshot? = null
        var offRouteChecked = false
        var announced = false
    }

    // --- item: normal current-generation onFix publishes the WHOLE group ---

    @Test
    fun onFixPattern_normalCurrentGeneration_publishesEveryMutationInTheGroup() {
        val gate = RouteGenerationGate()
        val state = FakeRepositoryState()
        val generation = gate.beginGeneration()
        val snapshot = snapshotFor(generation)
        state.activeSnapshot = snapshot

        // onFix(): capture the snapshot ONCE, compute purely outside any lock.
        val captured = state.activeSnapshot!!
        val fix = fixNear(LatLng(0.5, 0.0))
        val match = captured.matcher.match(fix)
        val prog = captured.progressCalculator.progress(match, fix)

        val accepted = gate.publishIfCurrent(captured.generation) {
            state.progress = prog
            state.offRouteChecked = true
            state.announced = true
        }

        assertTrue(accepted)
        assertEquals(prog, state.progress)
        assertTrue(state.offRouteChecked)
        assertTrue(state.announced)
    }

    // --- RACE 2/3: a stale fix resumes AFTER a newer generation is accepted -

    @Test
    fun onFixPattern_staleFixAfterNewerGenerationAccepted_discardsWholeGroupNotJustOneField() {
        val gate = RouteGenerationGate()
        val state = FakeRepositoryState()

        // A stale fix captures snapshot A and starts its (pure) computation.
        val genA = gate.beginGeneration()
        val snapshotA = snapshotFor(genA)
        state.activeSnapshot = snapshotA
        val staleCaptured = state.activeSnapshot!!
        val staleFix = fixNear(LatLng(0.25, 0.0))
        val staleMatch = staleCaptured.matcher.match(staleFix)
        val staleProg = staleCaptured.progressCalculator.progress(staleMatch, staleFix)

        // While that was "computing" (pure, no lock held), a newer route is
        // accepted for a different generation — exactly publishRoute()'s
        // build-snapshot-first-then-publish pattern.
        val genB = gate.beginGeneration()
        val snapshotB = snapshotFor(genB, straightRoute(destination = LatLng(2.0, 0.0)))
        val newRouteAccepted = gate.publishIfCurrent(genB) {
            state.activeSnapshot = snapshotB
            state.progress = null // a fresh route resets progress, as publishRoute() does
        }
        assertTrue(newRouteAccepted)

        // The stale fix now tries to publish its ENTIRE mutation group.
        val staleAccepted = gate.publishIfCurrent(staleCaptured.generation) {
            state.progress = staleProg
            state.offRouteChecked = true
            state.announced = true
        }

        // Discarded as a WHOLE — not one field discarded while another lands.
        assertFalse(staleAccepted)
        assertNull(state.progress)
        assertFalse(state.offRouteChecked)
        assertFalse(state.announced)
        // The repository is left exactly as generation B's publish set it.
        assertEquals(snapshotB, state.activeSnapshot)
    }

    // --- RACE 2: a stale fix resumes AFTER STOP -----------------------------

    @Test
    fun onFixPattern_staleFixAfterStop_neverPublishesAndNeverResurrectsSnapshot() {
        val gate = RouteGenerationGate()
        val state = FakeRepositoryState()

        val genA = gate.beginGeneration()
        val snapshotA = snapshotFor(genA)
        state.activeSnapshot = snapshotA
        val staleCaptured = state.activeSnapshot!!
        val staleFix = fixNear(LatLng(0.25, 0.0))
        val staleMatch = staleCaptured.matcher.match(staleFix)
        val staleProg = staleCaptured.progressCalculator.progress(staleMatch, staleFix)

        // STOP happens between the stale fix's snapshot capture and its
        // publish — invalidates the generation atomically with clearing.
        gate.stop {
            state.activeSnapshot = null
            state.progress = null
            state.message = null
        }

        val staleAccepted = gate.publishIfCurrent(staleCaptured.generation) {
            state.progress = staleProg
            state.activeSnapshot = staleCaptured
            state.offRouteChecked = true
        }

        assertFalse(staleAccepted)
        assertNull(state.progress)
        assertNull(state.activeSnapshot)
        assertFalse(state.offRouteChecked)
    }

    // --- RACE 3: torn-read prevention — one atomic reference, never mixed --

    @Test
    fun snapshotCapturedOnce_neverObservesAMixOfTwoGenerationsFields() {
        val gate = RouteGenerationGate()
        val state = FakeRepositoryState()

        val genA = gate.beginGeneration()
        val routeA = straightRoute(destination = LatLng(1.0, 0.0))
        state.activeSnapshot = snapshotFor(genA, routeA)

        // Publish a second route for a newer generation — a real repository
        // build a NEW MapMatcher/RouteProgressCalculator for it and swaps the
        // single `activeSnapshot` reference atomically (never three separate
        // field writes).
        val genB = gate.beginGeneration()
        val routeB = straightRoute(destination = LatLng(5.0, 0.0))
        val snapshotB = snapshotFor(genB, routeB)
        gate.publishIfCurrent(genB) { state.activeSnapshot = snapshotB }

        // A reader capturing the field ONCE can only ever see snapshot A in
        // full or snapshot B in full — the route, matcher and progress
        // calculator it reads always belong to the SAME generation, because
        // ActiveRouteSnapshot groups them into one immutable value instead of
        // three independently-mutable fields.
        val observed = state.activeSnapshot!!
        assertEquals(genB, observed.generation)
        assertEquals(routeB, observed.route)
        // The matcher/progressCalculator captured together with route B were
        // built FROM route B, not mixed with route A's geometry.
        val fixOnRouteB = fixNear(LatLng(2.5, 0.0))
        val match = observed.matcher.match(fixOnRouteB)
        // A match against route A's much shorter geometry would report a very
        // different (much larger, off-route) distance than one against route
        // B's geometry which actually passes near this point.
        assertTrue(match.offRouteMeters < 50_000.0)
    }

    // --- RACE 1: stale reroute failure never overwrites newer state --------

    @Test
    fun rerouteFailurePattern_staleFailureAfterNewerGenerationAccepted_neverOverwritesMessage() {
        val gate = RouteGenerationGate()
        val state = FakeRepositoryState()

        val staleGeneration = gate.beginGeneration()

        // A newer reroute (or a newer initial route) is accepted in the
        // meantime and sets its own message/state.
        val newGeneration = gate.beginGeneration()
        val newAccepted = gate.publishIfCurrent(newGeneration) {
            state.message = "Route in corso (nuova generazione)"
        }
        assertTrue(newAccepted)

        // The stale generation's reroute now fails and tries to publish a
        // failure message — exactly publishRerouteFailure()'s gated write.
        val staleFailureAccepted = gate.publishIfCurrent(staleGeneration) {
            state.message = "Ricalcolo non riuscito (stale)."
        }

        assertFalse(staleFailureAccepted)
        assertEquals("Route in corso (nuova generazione)", state.message)
    }

    @Test
    fun rerouteFailurePattern_currentGenerationFailure_isPublishedHonestly() {
        val gate = RouteGenerationGate()
        val state = FakeRepositoryState()
        val generation = gate.beginGeneration()

        val accepted = gate.publishIfCurrent(generation) {
            state.message = "Ricalcolo non riuscito (no_graph)."
        }

        assertTrue(accepted)
        assertEquals("Ricalcolo non riuscito (no_graph).", state.message)
    }
}
