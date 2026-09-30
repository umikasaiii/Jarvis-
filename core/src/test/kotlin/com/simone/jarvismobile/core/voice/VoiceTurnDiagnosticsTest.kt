package com.simone.jarvismobile.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceTurnDiagnosticsTest {

    private fun full(
        bargeInAtMs: Long? = null,
        playbackStartAtMs: Long? = null,
        speechStartedAtMs: Long? = null,
        speechEndedAtMs: Long? = null,
    ) = VoiceTurnTimestamps(
        sttStartedAtMs = 0,
        sttFinalAtMs = 300,
        answerStartedAtMs = 300,
        answerReadyAtMs = 900,
        ttsRequestedAtMs = 900,
        ttsFinishedAtMs = 2400,
        bargeInRequestedAtMs = bargeInAtMs,
        ttsPlaybackStartAtMs = playbackStartAtMs,
        speechStartedAtMs = speechStartedAtMs,
        speechEndedAtMs = speechEndedAtMs,
    )

    @Test
    fun `durations are computed correctly from complete timestamps`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t1",
            startedAtEpochMs = 1_000_000L,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(),
            finishedAtMs = 2400,
        )
        assertEquals(300, record.sttFinalLatencyMs)
        assertEquals(600, record.answerLatencyMs)
        assertEquals(1500, record.ttsDurationMs)
        assertEquals(2400, record.totalTurnMs)
        assertEquals(false, record.bargeInRequested)
        assertNull(record.ttsStoppedAfterBargeInMs)
        // No playback-start evidence was supplied — must stay unavailable,
        // never fall back to answerLatencyMs/ttsDurationMs or any other
        // already-known timestamp as a stand-in.
        assertNull(record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `missing timing points never fabricate a latency`() {
        val partial = VoiceTurnTimestamps(sttStartedAtMs = 0, sttFinalAtMs = null)
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t2",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.CANCELLED,
            failureStage = VoiceTurnFailureStage.STT,
            timestamps = partial,
            finishedAtMs = 150,
        )
        assertNull(record.sttFinalLatencyMs)
        assertNull(record.answerLatencyMs)
        assertNull(record.ttsDurationMs)
        assertNull(record.ttsStoppedAfterBargeInMs)
        assertNull(record.ttsPlaybackStartLatencyMs)
        // totalTurnMs is the one duration that is always known once the turn
        // finishes, whatever the outcome — it is not derived from a pair of
        // optional stage timestamps.
        assertEquals(150, record.totalTurnMs)
    }

    @Test
    fun `a fully empty timestamp set produces an all-null-latency record, not zeros`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t3",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.PERMISSION_DENIED,
            failureStage = VoiceTurnFailureStage.PERMISSION,
            timestamps = VoiceTurnTimestamps(),
            finishedAtMs = 5,
        )
        assertNull(record.sttFinalLatencyMs)
        assertNull(record.answerLatencyMs)
        assertNull(record.ttsDurationMs)
        assertEquals(false, record.bargeInRequested)
        assertNull(record.ttsStoppedAfterBargeInMs)
        assertNull(record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `cancelled turn interrupted during STT infers the STT stage`() {
        val timestamps = VoiceTurnTimestamps(sttStartedAtMs = 0, sttFinalAtMs = null)
        assertEquals(VoiceTurnFailureStage.STT, VoiceTurnDiagnostics.inferInterruptedStage(timestamps))
    }

    @Test
    fun `cancelled turn interrupted during answer generation infers that stage`() {
        val timestamps = VoiceTurnTimestamps(
            sttStartedAtMs = 0, sttFinalAtMs = 300,
            answerStartedAtMs = 300, answerReadyAtMs = null,
        )
        assertEquals(VoiceTurnFailureStage.ANSWER_GENERATION, VoiceTurnDiagnostics.inferInterruptedStage(timestamps))
    }

    @Test
    fun `cancelled turn interrupted during tts infers the TTS stage`() {
        val timestamps = VoiceTurnTimestamps(
            sttStartedAtMs = 0, sttFinalAtMs = 300,
            answerStartedAtMs = 300, answerReadyAtMs = 900,
            ttsRequestedAtMs = 900, ttsFinishedAtMs = null,
        )
        assertEquals(VoiceTurnFailureStage.TTS, VoiceTurnDiagnostics.inferInterruptedStage(timestamps))
    }

    @Test
    fun `a turn interrupted with every timestamp already set infers UNKNOWN, never a wrong stage`() {
        assertEquals(VoiceTurnFailureStage.UNKNOWN, VoiceTurnDiagnostics.inferInterruptedStage(full()))
    }

    @Test
    fun `permission-denied is inferred regardless of any other timestamp`() {
        assertEquals(
            VoiceTurnFailureStage.PERMISSION,
            VoiceTurnDiagnostics.inferInterruptedStage(VoiceTurnTimestamps(), permissionDenied = true),
        )
    }

    @Test
    fun `barge-in stop latency is claimed only when the request precedes this turn's own tts end`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t4",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(bargeInAtMs = 1_200),
            finishedAtMs = 2400,
        )
        assertEquals(true, record.bargeInRequested)
        assertEquals(1200, record.ttsStoppedAfterBargeInMs)
    }

    @Test
    fun `a barge-in request logically after this turn's own tts end never yields a negative latency`() {
        // Simulates the request having actually belonged to a later turn:
        // never invent a value here, even though the raw subtraction would
        // be tempting to compute.
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t5",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(bargeInAtMs = 5_000),
            finishedAtMs = 2400,
        )
        assertEquals(true, record.bargeInRequested)
        assertNull(record.ttsStoppedAfterBargeInMs)
    }

    @Test
    fun `bounded history never grows past the given max size`() {
        var history = emptyList<VoiceTurnDiagnostics>()
        repeat(50) { i ->
            val entry = VoiceTurnDiagnostics.compute(
                turnId = "turn-$i",
                startedAtEpochMs = i.toLong(),
                followUpIndex = i,
                cancellationRequested = false,
                outcome = VoiceTurnOutcome.COMPLETED,
                failureStage = VoiceTurnFailureStage.NONE,
                timestamps = VoiceTurnTimestamps(),
                finishedAtMs = 10,
            )
            history = history.appendBounded(entry, maxSize = 20)
        }
        assertEquals(20, history.size)
        // The oldest 30 entries were dropped; the list holds exactly the last 20.
        assertEquals("turn-30", history.first().turnId)
        assertEquals("turn-49", history.last().turnId)
    }

    @Test
    fun `follow-up index is preserved verbatim on the finished record`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t6",
            startedAtEpochMs = 0,
            followUpIndex = 3,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = VoiceTurnTimestamps(),
            finishedAtMs = 10,
        )
        assertEquals(3, record.followUpIndex)
    }

    @Test
    fun `cancellationRequested is preserved verbatim on the finished record`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t7",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = true,
            outcome = VoiceTurnOutcome.CANCELLED,
            failureStage = VoiceTurnFailureStage.STT,
            timestamps = VoiceTurnTimestamps(),
            finishedAtMs = 10,
        )
        assertEquals(true, record.cancellationRequested)
        assertEquals(VoiceTurnOutcome.CANCELLED, record.outcome)
    }

    // --- Live Voice Phase 0.2 — ttsPlaybackStartLatencyMs -------------------

    @Test
    fun `matching playback-start evidence produces a real, non-fabricated latency`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t8",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(playbackStartAtMs = 1_100),
            finishedAtMs = 2400,
        )
        // ttsRequestedAtMs=900, ttsPlaybackStartAtMs=1100 -> 200ms, the exact
        // request-to-playback-start gap, never derived from anything else
        // (not ttsDurationMs, not answerLatencyMs).
        assertEquals(200, record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `no playback-start evidence leaves the metric unavailable, not zero`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t9",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(playbackStartAtMs = null),
            finishedAtMs = 2400,
        )
        assertNull(record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `playback-start evidence timestamped before the request is rejected, never a negative latency`() {
        // Simulates a stale callback that belonged to an earlier, already-
        // superseded invocation slipping past invocation-id fencing at the
        // engine/session layer — this ordering check is the model's own,
        // independent second line of defence: it must never turn that into
        // an invented negative duration.
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t10",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(playbackStartAtMs = 500), // before ttsRequestedAtMs=900
            finishedAtMs = 2400,
        )
        assertNull(record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `playback-start evidence exactly at the request instant is accepted, not just strictly after`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t11",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(playbackStartAtMs = 900), // == ttsRequestedAtMs
            finishedAtMs = 2400,
        )
        assertEquals(0, record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `barge-in requested before playback ever started leaves the metric unavailable`() {
        // The engine/session layer never fires a playback-started event once
        // barge-in actually cut the invocation off before it produced any
        // audio — modelled here as simply no playback-start evidence at all,
        // alongside a barge-in request.
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t12",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(bargeInAtMs = 950, playbackStartAtMs = null),
            finishedAtMs = 2400,
        )
        assertEquals(true, record.bargeInRequested)
        assertNull(record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `barge-in requested after a genuine playback start preserves the real start evidence`() {
        // Playback genuinely started at 1100, barge-in was requested later at
        // 1800 — the two are independent pieces of evidence: barge-in must
        // never retroactively erase a playback-start observation that
        // already happened.
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t13",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(bargeInAtMs = 1_800, playbackStartAtMs = 1_100),
            finishedAtMs = 2400,
        )
        assertEquals(true, record.bargeInRequested)
        assertEquals(200, record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `a cancelled turn with no playback-start evidence never fabricates one from the cancellation itself`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "t14",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = true,
            outcome = VoiceTurnOutcome.CANCELLED,
            failureStage = VoiceTurnFailureStage.TTS,
            timestamps = VoiceTurnTimestamps(ttsRequestedAtMs = 900, ttsPlaybackStartAtMs = null),
            finishedAtMs = 950,
        )
        assertNull(record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `the finished record carries only opaque ids, timestamps, enums, durations, counters and booleans`() {
        // Privacy/schema guard, enforced at the Kotlin type level (see the
        // VoiceTurnDiagnostics/VoiceTurnTimestamps declarations themselves:
        // every field is a Long?/Int/Boolean/enum, with turnId as the one
        // String field, an opaque generated id — never reply text/transcript
        // content). This test exercises that turnId really is opaque (a
        // caller-supplied id round-trips verbatim, never inspected/parsed/
        // transformed by compute()) rather than re-deriving the type check
        // the compiler already performs.
        val record = VoiceTurnDiagnostics.compute(
            turnId = "opaque-id-not-reply-text-15",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = VoiceTurnTimestamps(),
            finishedAtMs = 10,
        )
        assertEquals("opaque-id-not-reply-text-15", record.turnId)
    }

    // --- Live Voice Phase 0.3 — real user-speech boundary metrics ----------

    @Test
    fun `ordered start and end produce a real userSpeechDurationMs`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "s1",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(speechStartedAtMs = 50, speechEndedAtMs = 1_850),
            finishedAtMs = 2400,
        )
        assertEquals(1800, record.userSpeechDurationMs)
    }

    @Test
    fun `missing start or end leaves userSpeechDurationMs unavailable`() {
        val missingStart = VoiceTurnDiagnostics.compute(
            turnId = "s2a",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(speechStartedAtMs = null, speechEndedAtMs = 1_850),
            finishedAtMs = 2400,
        )
        assertNull(missingStart.userSpeechDurationMs)

        val missingEnd = VoiceTurnDiagnostics.compute(
            turnId = "s2b",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(speechStartedAtMs = 50, speechEndedAtMs = null),
            finishedAtMs = 2400,
        )
        assertNull(missingEnd.userSpeechDurationMs)
    }

    @Test
    fun `an end-before-start pair is rejected, never a negative userSpeechDurationMs`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "s3",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(speechStartedAtMs = 1_000, speechEndedAtMs = 500),
            finishedAtMs = 2400,
        )
        assertNull(record.userSpeechDurationMs)
    }

    @Test
    fun `sttFinalizationAfterSpeechMs is valid when speech ends before the STT final result`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "s4",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            // sttFinalAtMs=300 from full(); speech ended at 180 -> 120ms finalization.
            timestamps = full(speechStartedAtMs = 20, speechEndedAtMs = 180),
            finishedAtMs = 2400,
        )
        assertEquals(120, record.sttFinalizationAfterSpeechMs)
    }

    @Test
    fun `sttFinalizationAfterSpeechMs is rejected when speech end is after the STT final result`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "s5",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            // sttFinalAtMs=300 from full(); a stale/out-of-order speechEndedAtMs=400.
            timestamps = full(speechStartedAtMs = 20, speechEndedAtMs = 400),
            finishedAtMs = 2400,
        )
        assertNull(record.sttFinalizationAfterSpeechMs)
    }

    @Test
    fun `responsePlaybackAfterSpeechMs, the primary product metric, is valid when ordered correctly`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "s6",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            // ttsPlaybackStartAtMs=1300 from playbackStartAtMs; speech ended at 200.
            timestamps = full(speechStartedAtMs = 20, speechEndedAtMs = 200, playbackStartAtMs = 1_300),
            finishedAtMs = 2400,
        )
        assertEquals(1100, record.responsePlaybackAfterSpeechMs)
    }

    @Test
    fun `responsePlaybackAfterSpeechMs is rejected when playback-start evidence precedes speech end`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "s7",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(speechStartedAtMs = 20, speechEndedAtMs = 1_500, playbackStartAtMs = 1_100),
            finishedAtMs = 2400,
        )
        assertNull(record.responsePlaybackAfterSpeechMs)
    }

    @Test
    fun `no speech-boundary evidence at all leaves all three Phase 0_3 metrics unavailable, not zero`() {
        val record = VoiceTurnDiagnostics.compute(
            turnId = "s8",
            startedAtEpochMs = 0,
            followUpIndex = 0,
            cancellationRequested = false,
            outcome = VoiceTurnOutcome.COMPLETED,
            failureStage = VoiceTurnFailureStage.NONE,
            timestamps = full(),
            finishedAtMs = 2400,
        )
        assertNull(record.userSpeechDurationMs)
        assertNull(record.sttFinalizationAfterSpeechMs)
        assertNull(record.responsePlaybackAfterSpeechMs)
    }
}
