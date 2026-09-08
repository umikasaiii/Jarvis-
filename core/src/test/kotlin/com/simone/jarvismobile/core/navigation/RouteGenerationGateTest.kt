package com.simone.jarvismobile.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the JARVIS Implementation Master Plan PASSAGGIO 9 generation
 * arbitration scenarios (§14) at the one place they can be tested on a
 * plain JVM, independent of [com.simone.jarvismobile.navigation.NavigationRepository]'s
 * Android-`Context` dependencies.
 */
class RouteGenerationGateTest {

    // --- item 1/13: latest accepted generation wins, even out of order ---

    @Test
    fun requestAThenBThenBFinishesFirstThenAFinishesLastLeavesBAuthoritative() {
        val gate = RouteGenerationGate()
        val applied = mutableListOf<String>()

        val genA = gate.beginGeneration()
        val genB = gate.beginGeneration() // B supersedes A before A ever publishes

        // B finishes first.
        assertTrue(gate.publishIfCurrent(genB) { applied += "B" })
        // A finishes last — must be discarded, never overwrite B.
        assertFalse(gate.publishIfCurrent(genA) { applied += "A" })

        assertEquals(listOf("B"), applied)
    }

    @Test
    fun rapidAThenBThenCPublishesOnlyC() {
        val gate = RouteGenerationGate()
        val applied = mutableListOf<String>()

        val genA = gate.beginGeneration()
        val genB = gate.beginGeneration()
        val genC = gate.beginGeneration()

        // All three "finish" in scramble order; only C's generation is current.
        assertFalse(gate.publishIfCurrent(genB) { applied += "B" })
        assertTrue(gate.publishIfCurrent(genC) { applied += "C" })
        assertFalse(gate.publishIfCurrent(genA) { applied += "A" })

        assertEquals(listOf("C"), applied)
    }

    // --- item 2: escaping cancellation does not matter, the check still discards ---

    @Test
    fun aResultThatIgnoresCancellationAndReturnsAnywayIsStillDiscardedByGeneration() {
        val gate = RouteGenerationGate()
        val genA = gate.beginGeneration()
        gate.beginGeneration() // supersedes A; A's "job" is imagined cancelled but keeps running anyway

        var applied = false
        // A "finishes" regardless of its cancellation having been requested —
        // the gate's job is to be correct even when cancellation didn't work.
        val accepted = gate.publishIfCurrent(genA) { applied = true }

        assertFalse(accepted)
        assertFalse(applied)
    }

    // --- item 3/14: STOP invalidates outstanding work, late results cannot resurrect ---

    @Test
    fun stopWhileARequestIsPendingMakesItsLateResultUnableToResurrectState() {
        val gate = RouteGenerationGate()
        val genA = gate.beginGeneration()
        var cleared = false

        gate.stop { cleared = true }
        assertTrue(cleared)

        var applied = false
        val accepted = gate.publishIfCurrent(genA) { applied = true }

        assertFalse(accepted)
        assertFalse(applied)
    }

    // --- item 4: stop then start again uses a distinct, never-reused generation ---

    @Test
    fun stopThenStartAgainProducesADistinctGenerationThatIsAccepted() {
        val gate = RouteGenerationGate()
        val genA = gate.beginGeneration()
        gate.stop { }
        val genB = gate.beginGeneration()

        assertTrue(genB != genA)
        assertTrue(gate.publishIfCurrent(genB) { })
    }

    // --- item 5/6: a reroute is a new generation; a failed reroute cannot resurrect the old one ---

    @Test
    fun rerouteProducesANewGenerationDistinctFromTheRouteItSupersedes() {
        val gate = RouteGenerationGate()
        val initialRoute = gate.beginGeneration()
        val reroute = gate.beginGeneration()

        assertTrue(reroute != initialRoute)
    }

    @Test
    fun aFailedRerouteNeverCausesAnOlderStaleComputationToBecomeCurrent() {
        val gate = RouteGenerationGate()
        val staleInitial = gate.beginGeneration()
        gate.beginGeneration() // the reroute's own generation - it will "fail" and never publish

        // The failed reroute publishes nothing; the stale initial computation
        // finishing afterward must still be discarded, not promoted by default.
        assertFalse(gate.publishIfCurrent(staleInitial) { })
    }

    // --- item 11: an exception thrown while publishing/clearing propagates, never swallowed ---

    @Test
    fun anExceptionThrownFromPublishPropagatesOutOfPublishIfCurrent() {
        val gate = RouteGenerationGate()
        val gen = gate.beginGeneration()
        var threw = false
        try {
            gate.publishIfCurrent(gen) { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun anExceptionThrownFromClearPropagatesOutOfStop() {
        val gate = RouteGenerationGate()
        var threw = false
        try {
            gate.stop { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    // --- item 15: one normal, uncontested request still works ---

    @Test
    fun oneNormalUncontestedRequestPublishes() {
        val gate = RouteGenerationGate()
        val gen = gate.beginGeneration()
        var applied = false
        assertTrue(gate.publishIfCurrent(gen) { applied = true })
        assertTrue(applied)
    }

    // --- current() reflects the latest begin/stop -------------------------

    @Test
    fun currentAlwaysReflectsTheMostRecentBeginOrStop() {
        val gate = RouteGenerationGate()
        val genA = gate.beginGeneration()
        assertEquals(genA, gate.current())
        val genB = gate.beginGeneration()
        assertEquals(genB, gate.current())
        val postStop = gate.stop { }
        assertEquals(postStop, gate.current())
    }
}
