package com.simone.jarvismobile.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VadTurnPolicyTest {
    private val cfg = VadTurnConfig(minSpeechFrames = 3, minSilenceFrames = 4)
    private fun feed(p: VadTurnPolicy, start: Long, vararg obs: VadObservation): List<VadTurnEvent?> =
        obs.mapIndexed { i, o -> p.observe(start + i, o) }

    private val S = VadObservation.SPEECH
    private val N = VadObservation.NON_SPEECH
    private val U = VadObservation.UNKNOWN

    @Test fun silenceOnlyProducesNoEvents() {
        val p = VadTurnPolicy(cfg)
        assertTrue(feed(p, 0, N, N, N, N, N, N, N, N).all { it == null })
    }

    @Test fun singleSpeechFrameDoesNotStart() {
        val p = VadTurnPolicy(cfg)
        assertTrue(feed(p, 0, S, N, N).all { it == null })
        assertFalse(p.isSpeechActive)
    }

    @Test fun enoughSpeechStartsExactlyOnce() {
        val p = VadTurnPolicy(cfg)
        val ev = feed(p, 0, S, S, S)
        assertEquals(listOf(null, null, VadTurnEvent.SPEECH_STARTED), ev)
        assertTrue(p.isSpeechActive)
    }

    @Test fun sustainedSpeechDoesNotDuplicateStart() {
        val p = VadTurnPolicy(cfg)
        val ev = feed(p, 0, S, S, S, S, S, S, S, S)
        assertEquals(1, ev.count { it == VadTurnEvent.SPEECH_STARTED })
    }

    @Test fun singleSilenceFrameDoesNotEnd() {
        val p = VadTurnPolicy(cfg)
        feed(p, 0, S, S, S)
        assertNull(p.observe(3, N))
        assertTrue(p.isSpeechActive)
    }

    @Test fun enoughSilenceEndsExactlyOnce() {
        val p = VadTurnPolicy(cfg)
        feed(p, 0, S, S, S)
        val ev = feed(p, 3, N, N, N, N, N, N)
        assertEquals(1, ev.count { it == VadTurnEvent.SPEECH_ENDED })
        assertEquals(VadTurnEvent.SPEECH_ENDED, ev[3])
        assertFalse(p.isSpeechActive)
    }

    @Test fun speechResumingBeforeThresholdDoesNotEnd() {
        val p = VadTurnPolicy(cfg)
        feed(p, 0, S, S, S)
        val ev = feed(p, 3, N, N, N, S, N, N, N)
        assertTrue(ev.all { it == null })
        assertTrue(p.isSpeechActive)
    }

    @Test fun resetClearsState() {
        val p = VadTurnPolicy(cfg)
        feed(p, 0, S, S, S)
        p.reset()
        assertFalse(p.isSpeechActive)
        // sequence tracking also reset: starting again at 0 is accepted
        assertEquals(listOf(null, null, VadTurnEvent.SPEECH_STARTED), feed(p, 0, S, S, S))
    }

    @Test fun invalidProbabilityIsUnknownNotClamped() {
        assertEquals(U, VadObservation.fromProbability(Float.NaN, 0.5f))
        assertEquals(U, VadObservation.fromProbability(-0.1f, 0.5f))
        assertEquals(U, VadObservation.fromProbability(1.1f, 0.5f))
        assertEquals(U, VadObservation.fromProbability(Float.POSITIVE_INFINITY, 0.5f))
        assertEquals(U, VadObservation.fromProbability(0.6f, 2f))
        assertEquals(S, VadObservation.fromProbability(0.6f, 0.5f))
        assertEquals(N, VadObservation.fromProbability(0.4f, 0.5f))
    }

    @Test fun countersAreBoundedAndNeverOverflow() {
        val p = VadTurnPolicy(VadTurnConfig(1, 1))
        var starts = 0; var ends = 0
        for (i in 0L until 100_000L) {
            when (p.observe(i, if ((i / 1000) % 2 == 0L) S else N)) {
                VadTurnEvent.SPEECH_STARTED -> starts++
                VadTurnEvent.SPEECH_ENDED -> ends++
                null -> {}
            }
        }
        assertTrue(starts > 0 && starts - ends in 0..1)
        assertFailsWith<IllegalArgumentException> { VadTurnConfig(0, 1) }
        assertFailsWith<IllegalArgumentException> { VadTurnConfig(1, VadTurnConfig.MAX_FRAMES + 1) }
    }

    @Test fun sequenceGapResetsPendingEvidenceDeterministically() {
        val p = VadTurnPolicy(cfg)
        assertNull(p.observe(0, S)); assertNull(p.observe(1, S))
        // gap: frame 2 lost -> pending run reset; the third SPEECH alone must not start
        assertNull(p.observe(3, S))
        assertFalse(p.isSpeechActive)
        assertNull(p.observe(4, S)); assertEquals(VadTurnEvent.SPEECH_STARTED, p.observe(5, S))
    }

    @Test fun staleOrDuplicateSequenceIsIgnored() {
        val p = VadTurnPolicy(cfg)
        p.observe(5, S); p.observe(6, S)
        assertNull(p.observe(6, S)) // duplicate: not counted
        assertNull(p.observe(2, S)) // stale: not counted
        assertEquals(VadTurnEvent.SPEECH_STARTED, p.observe(7, S))
    }

    @Test fun unknownIsNoEvidenceAndNeverEndsSpeech() {
        val p = VadTurnPolicy(cfg)
        feed(p, 0, S, S, S)
        val ev = feed(p, 3, N, N, N, U, N, N, N)
        assertTrue(ev.all { it == null }) // U reset the silence run
        assertTrue(p.isSpeechActive)
    }

    @Test fun observationModelCarriesNoTextOrPersonalData() {
        // closed-world enum + primitives only
        assertEquals(setOf("SPEECH", "NON_SPEECH", "UNKNOWN"), VadObservation.entries.map { it.name }.toSet())
        assertEquals(setOf("SPEECH_STARTED", "SPEECH_ENDED"), VadTurnEvent.entries.map { it.name }.toSet())
    }
}
