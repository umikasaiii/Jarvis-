package com.simone.jarvismobile.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceTurnDiagnosticsTest {

    private fun full(bargeInAtMs: Long? = null) = VoiceTurnTimestamps(
        sttStartedAtMs = 0,
        sttFinalAtMs = 300,
        answerStartedAtMs = 300,
        answerReadyAtMs = 900,
        ttsRequestedAtMs = 900,
        ttsFinishedAtMs = 2400,
        bargeInRequestedAtMs = bargeInAtMs,
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
}
