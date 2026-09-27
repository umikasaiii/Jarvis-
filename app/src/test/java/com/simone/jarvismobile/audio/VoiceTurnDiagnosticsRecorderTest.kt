package com.simone.jarvismobile.audio

import com.simone.jarvismobile.core.voice.VoiceTurnFailureStage
import com.simone.jarvismobile.core.voice.VoiceTurnOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live Voice Phase 0.2 — [VoiceTurnDiagnosticsRecorder.markTtsPlaybackStarted]
 * has zero Android dependencies (same as the rest of this class), so it is
 * exercised here directly on the JVM rather than only through
 * [com.simone.jarvismobile.core.voice.VoiceTurnDiagnosticsTest]'s pure
 * model-level coverage — these tests pin the *wiring*: that a stale/late
 * call after a turn has already finished cannot mutate anything, that a
 * second observation for the same turn is ignored (only the first counts),
 * and that the raw mark ends up producing the same latency the pure
 * `compute()` math already guarantees.
 */
class VoiceTurnDiagnosticsRecorderTest {

    @Test
    fun `a playback-start call with no in-flight turn is a safe no-op`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        // No beginTurn() call at all — nothing to mutate, must not throw.
        recorder.markTtsPlaybackStarted()
        assertEquals(0, recorder.history.value.size)
    }

    @Test
    fun `a late playback-start call after the turn already finished never mutates the finished record`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markTtsRequested()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val before = recorder.history.value.single()
        assertNull(before.ttsPlaybackStartLatencyMs)

        // A stale event arriving after finish() — must not throw, and must
        // not retroactively change the already-published record.
        recorder.markTtsPlaybackStarted()

        val after = recorder.history.value.single()
        assertEquals(before, after)
        assertNull(after.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `only the first playback-start observation is kept for a turn`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markTtsRequested()
        recorder.markTtsPlaybackStarted()
        // A short, real wait so a second (wrongly-accepted) observation
        // would measurably move the latency forward if it were not ignored.
        Thread.sleep(60)
        recorder.markTtsPlaybackStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertTrue(
            "second observation must be ignored, but latency was ${record.ttsPlaybackStartLatencyMs}ms",
            (record.ttsPlaybackStartLatencyMs ?: Long.MAX_VALUE) < 60,
        )
    }

    @Test
    fun `a matching request-then-playback-start pair produces a real latency end to end`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markTtsRequested()
        Thread.sleep(10)
        recorder.markTtsPlaybackStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        val latency = record.ttsPlaybackStartLatencyMs
        assertTrue("expected a non-null, non-negative latency, got $latency", latency != null && latency >= 0)
    }

    @Test
    fun `playback-start observed without a preceding request mark never fabricates a latency`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        // No markTtsRequested() at all — mirrors an unmarked speakOut() call
        // (e.g. an error-message utterance outside the tracked answer path).
        recorder.markTtsPlaybackStarted()
        recorder.finish(VoiceTurnOutcome.NO_SPEECH, VoiceTurnFailureStage.STT)

        val record = recorder.history.value.single()
        assertNull(record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `a new turn after a finished one starts with no playback-start evidence of its own`() {
        val recorder = VoiceTurnDiagnosticsRecorder()

        recorder.beginTurn(followUpIndex = 0)
        recorder.markTtsRequested()
        recorder.markTtsPlaybackStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        recorder.beginTurn(followUpIndex = 1)
        recorder.markTtsRequested()
        // No markTtsPlaybackStarted() for this second turn.
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val turns = recorder.history.value
        assertEquals(2, turns.size)
        assertNull(turns[1].ttsPlaybackStartLatencyMs)
    }
}
