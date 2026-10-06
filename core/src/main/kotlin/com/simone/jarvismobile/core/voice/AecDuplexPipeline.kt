package com.simone.jarvismobile.core.voice

/** Typed, bounded evidence about duplex timing. Fields are null whenever no REAL evidence exists. */
data class DuplexTimingObservation(
    val farFramesFed: Long = 0,
    val nearFramesProcessed: Long = 0,
    /** Monotonic ns between the first accepted far-end block and the first near-end block processed. Real host-clock ordering evidence. */
    val firstFarToFirstNearNs: Long? = null,
    /** Delay hint handed to AEC3. ALWAYS null: JARVIS cannot produce a trustworthy external delay; AEC3 estimates its own. */
    val externalDelayHintMs: Long? = null,
    /** Delay reported back by AEC3 itself, only if the engine really exposes one. */
    val aecReportedDelayMs: Long? = null,
)

/** Bounded counters for the AEC path (no PCM). */
data class AecPipelineSnapshot(
    val state: EchoCancellerState = EchoCancellerState.UNAVAILABLE,
    val farBlocksFed: Long = 0,
    val farBlocksRejected: Long = 0,
    val nearBlocksProcessed: Long = 0,
    val nearBlocksFailed: Long = 0,
    val outputFrames: Long = 0,
    val resetCount: Long = 0,
    val lastFailure: EchoCancellerFailure? = null,
    val avgProcessMs: Double? = null,
    val maxProcessMs: Double = 0.0,
    val nearRateHz: Int = 0,
    val farRateHz: Int = 0,
    val timing: DuplexTimingObservation = DuplexTimingObservation(),
)

/**
 * LV-R1 — near/far framing around an [EchoCanceller]: 512-sample near frames -> exact 160-sample
 * 10 ms blocks -> AEC -> re-assembled into 512-sample post-AEC [VoicePcmFrame]s for Silero.
 * Far-end frames (any supported rate) -> exact rate/100 blocks -> render input.
 *
 * Exact sample conservation (see [ShortBlockAssembler]); a near sequence gap or a far generation /
 * rate change resets the affected assemblers (no invented samples). Far-end authority is the
 * FarEndPcmFrame stream only. Thread model: [onFarEnd] and [onNearEnd] may run on different threads
 * (render/capture), each guarded by its own lock; the canceller must tolerate that pair.
 */
class AecDuplexPipeline(
    private val canceller: EchoCanceller,
    private val nanoTime: () -> Long = System::nanoTime,
    private val outputFrameSamples: Int = PcmCaptureEngine.DEFAULT_FRAME_SAMPLES,
) {
    private val farLock = Any()
    private val nearLock = Any()
    private var farAssembler: FloatBlockAssembler? = null
    private var farGeneration: Int = Int.MIN_VALUE
    private var farRate = 0
    private var farLastSeq = -1L
    private val nearAssembler = ShortBlockAssembler(canceller.nearBlockSamples)
    private val outAssembler = ShortBlockAssembler(outputFrameSamples)
    private var nearLastSeq = -1L
    private var outSeq = 0L
    private var farBlocksFed = 0L
    private var farBlocksRejected = 0L
    private var nearBlocksProcessed = 0L
    private var nearBlocksFailed = 0L
    private var outputFrames = 0L
    private var resetCount = 0L
    private var lastFailure: EchoCancellerFailure? = null
    private var totalNs = 0L
    private var maxNs = 0L
    @Volatile private var firstFarNs: Long? = null
    @Volatile private var firstNearNs: Long? = null
    private var nearRate = 0

    /** Returns false when the frame was refused (unsupported rate / closed / failure). */
    fun onFarEnd(frame: FarEndPcmFrame): Boolean = synchronized(farLock) {
        val rate = frame.sampleRateHz
        val blockSize = EchoRatePolicy.blockSamples(rate)
        if (!EchoRatePolicy.farRateSupported(rate) || blockSize == null || blockSize != canceller.farBlockSamples) {
            lastFailure = EchoCancellerFailure.UNSUPPORTED_FAR_RATE
            farBlocksRejected++
            return false
        }
        if (farAssembler == null || frame.generation != farGeneration || rate != farRate ||
            (farLastSeq >= 0 && frame.sequence != farLastSeq + 1)
        ) {
            if (farAssembler != null) resetCount++
            farAssembler = FloatBlockAssembler(blockSize)
            farGeneration = frame.generation
            farRate = rate
        }
        farLastSeq = frame.sequence
        val samples = frame.copyOfSamples()
        var ok = true
        farAssembler!!.push(samples, samples.size) { block ->
            when (val r = canceller.feedFarEnd(block)) {
                is EchoBlockResult.Failed -> { lastFailure = r.failure; farBlocksRejected++; ok = false }
                else -> {
                    farBlocksFed++
                    if (firstFarNs == null) firstFarNs = nanoTime()
                }
            }
        }
        ok
    }

    /** Feeds one near-end frame; returns zero or more post-AEC frames (usually exactly one). */
    fun onNearEnd(frame: VoicePcmFrame): List<VoicePcmFrame> = synchronized(nearLock) {
        if (frame.sampleRateHz != EchoRatePolicy.NEAR_RATE_HZ || frame.channelCount != 1) {
            lastFailure = EchoCancellerFailure.UNSUPPORTED_NEAR_RATE
            return emptyList()
        }
        nearRate = frame.sampleRateHz
        if (nearLastSeq >= 0 && frame.sequence != nearLastSeq + 1) {
            if (frame.sequence <= nearLastSeq) return emptyList() // stale/duplicate
            nearAssembler.reset(); outAssembler.reset(); resetCount++
            outSeq++ // make the continuity break visible to Silero/VadTurnPolicy
        }
        nearLastSeq = frame.sequence
        val out = ArrayList<VoicePcmFrame>(1)
        val samples = frame.copyOfSamples()
        nearAssembler.push(samples, samples.size) { block ->
            val t0 = nanoTime()
            when (val r = canceller.processNearEnd(block)) {
                is EchoBlockResult.Ok -> {
                    val dt = nanoTime() - t0
                    totalNs += dt; if (dt > maxNs) maxNs = dt
                    nearBlocksProcessed++
                    if (firstNearNs == null) firstNearNs = nanoTime()
                    outAssembler.push(r.samples, r.samples.size) { chunk ->
                        VoicePcmFrame.copyOf(chunk, chunk.size, EchoRatePolicy.NEAR_RATE_HZ, outSeq)?.let {
                            outSeq++; outputFrames++; out.add(it)
                        }
                    }
                }
                is EchoBlockResult.Failed -> { lastFailure = r.failure; nearBlocksFailed++ }
                EchoBlockResult.Accepted -> Unit
            }
        }
        out
    }

    fun snapshot(): AecPipelineSnapshot {
        val fa = synchronized(farLock) { Triple(farBlocksFed, farBlocksRejected, farRate) }
        return synchronized(nearLock) {
            AecPipelineSnapshot(
                state = canceller.state,
                farBlocksFed = fa.first, farBlocksRejected = fa.second,
                nearBlocksProcessed = nearBlocksProcessed, nearBlocksFailed = nearBlocksFailed,
                outputFrames = outputFrames, resetCount = resetCount, lastFailure = lastFailure,
                avgProcessMs = if (nearBlocksProcessed > 0) totalNs / nearBlocksProcessed / 1_000_000.0 else null,
                maxProcessMs = maxNs / 1_000_000.0,
                nearRateHz = nearRate, farRateHz = fa.third,
                timing = DuplexTimingObservation(
                    farFramesFed = fa.first, nearFramesProcessed = nearBlocksProcessed,
                    firstFarToFirstNearNs = if (firstFarNs != null && firstNearNs != null) firstNearNs!! - firstFarNs!! else null,
                ),
            )
        }
    }

    fun close() = canceller.close()
}
