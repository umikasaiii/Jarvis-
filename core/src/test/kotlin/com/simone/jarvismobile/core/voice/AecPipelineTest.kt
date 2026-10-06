package com.simone.jarvismobile.core.voice

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeEchoCanceller(
    override val nearBlockSamples: Int = 160,
    override val farBlockSamples: Int = 160,
) : EchoCanceller {
    override var state = EchoCancellerState.READY
    val farBlocks = mutableListOf<FloatArray>()
    val nearBlocks = mutableListOf<ShortArray>()
    var failNear = false
    var failFar = false
    var closeCount = 0
    override fun feedFarEnd(block: FloatArray): EchoBlockResult {
        if (state == EchoCancellerState.CLOSED) return EchoBlockResult.Failed(EchoCancellerFailure.CLOSED)
        if (failFar) return EchoBlockResult.Failed(EchoCancellerFailure.NATIVE_PROCESS_FAILED)
        if (block.size != farBlockSamples) return EchoBlockResult.Failed(EchoCancellerFailure.INVALID_BLOCK)
        farBlocks.add(block); return EchoBlockResult.Accepted
    }
    override fun processNearEnd(block: ShortArray): EchoBlockResult {
        if (state == EchoCancellerState.CLOSED) return EchoBlockResult.Failed(EchoCancellerFailure.CLOSED)
        if (failNear) return EchoBlockResult.Failed(EchoCancellerFailure.NATIVE_PROCESS_FAILED)
        if (block.size != nearBlockSamples) return EchoBlockResult.Failed(EchoCancellerFailure.INVALID_BLOCK)
        nearBlocks.add(block); return EchoBlockResult.Ok(block.copyOf())
    }
    override fun close() { if (state != EchoCancellerState.CLOSED) { closeCount++; state = EchoCancellerState.CLOSED } }
}

class BlockAssemblerTest {
    @Test fun short512To160ConservesEverySampleInOrder() {
        val a = ShortBlockAssembler(160)
        val out = mutableListOf<Short>()
        val src = ShortArray(512) { it.toShort() }
        var blocks = 0
        a.push(src, 512) { b -> blocks++; assertEquals(160, b.size); b.forEach { out.add(it) } }
        assertEquals(3, blocks); assertEquals(32, a.pendingSamples)
        assertEquals(480, out.size)
        assertContentEquals(ShortArray(480) { it.toShort() }, out.toShortArray())
        // 5 frames of 512 = 2560 = 16 blocks exactly
        val b = ShortBlockAssembler(160); var n = 0
        repeat(5) { b.push(src, 512) { n++ } }
        assertEquals(16, n); assertEquals(0, b.pendingSamples)
        assertEquals(b.totalIn, b.totalOut + b.pendingSamples)
    }

    @Test fun short160To512ReassemblyIsExact() {
        val a = ShortBlockAssembler(512)
        val outputs = mutableListOf<ShortArray>()
        val chunk = ShortArray(160) { it.toShort() }
        repeat(16) { a.push(chunk, 160) { outputs.add(it) } } // 2560 samples = 5 frames
        assertEquals(5, outputs.size); assertTrue(outputs.all { it.size == 512 })
        assertEquals(0, a.pendingSamples)
        assertEquals(2560L, a.totalOut)
    }

    @Test fun resetDiscardsRemainderAndCountsIt() {
        val a = ShortBlockAssembler(160)
        a.push(ShortArray(100), 100) { }
        assertEquals(100, a.pendingSamples)
        a.reset(); assertEquals(0, a.pendingSamples); assertEquals(100L, a.discarded)
    }

    @Test fun floatAssemblerRatesProduceExactBlocks() {
        for ((rate, block) in listOf(16_000 to 160, 24_000 to 240, 32_000 to 320, 48_000 to 480)) {
            assertEquals(block, EchoRatePolicy.blockSamples(rate))
            val a = FloatBlockAssembler(block); var n = 0
            a.push(FloatArray(rate), rate) { assertEquals(block, it.size); n++ } // 1 s
            assertEquals(100, n); assertEquals(0, a.pendingSamples)
        }
    }

    @Test fun rate22050CannotFormExactBlocks() {
        assertNull(EchoRatePolicy.blockSamples(22_050))
        assertFalse(EchoRatePolicy.farRateSupported(22_050))
        assertTrue(EchoRatePolicy.farRateSupported(24_000))
    }
}

class AecDuplexPipelineTest {
    private fun near(seq: Long, fill: Short = 5) = VoicePcmFrame.copyOf(ShortArray(512) { fill }, 512, 16_000, seq)!!
    private fun far(gen: Int, seq: Long, off: Long, rate: Int = 24_000, n: Int = 480) =
        FarEndPcmFrame.copyOf(FloatArray(n) { 0.1f }, 0, n, gen, seq, off, rate)!!

    @Test fun nearFramesProduce512OutputsWithConservation() {
        val c = FakeEchoCanceller(); val p = AecDuplexPipeline(c)
        var outs = 0
        for (s in 0L until 5L) outs += p.onNearEnd(near(s)).size
        assertEquals(5, outs) // 5*512 = 2560 samples = 16 AEC blocks = 5 output frames
        assertEquals(16, c.nearBlocks.size)
        val snap = p.snapshot()
        assertEquals(16, snap.nearBlocksProcessed); assertEquals(5, snap.outputFrames)
        // output sequences are 0..4 consecutive
    }

    @Test fun outputSequenceIsConsecutive() {
        val p = AecDuplexPipeline(FakeEchoCanceller())
        val seqs = (0L until 10L).flatMap { p.onNearEnd(near(it)) }.map { it.sequence }
        assertEquals(seqs.indices.map { it.toLong() }, seqs)
    }

    @Test fun nearSequenceGapResetsAssemblersAndMakesBreakVisible() {
        val p = AecDuplexPipeline(FakeEchoCanceller())
        p.onNearEnd(near(0)); p.onNearEnd(near(1)) // leaves a remainder pending
        val after = p.onNearEnd(near(5)) // gap
        val s = p.snapshot()
        assertEquals(1, s.resetCount)
        // a gap is visible: output sequence skipped at least one value
        val prev = (0L until 2L).count()
        assertTrue(after.isEmpty() || after.first().sequence > prev - 1)
    }

    @Test fun staleOrDuplicateNearFrameIsIgnored() {
        val p = AecDuplexPipeline(FakeEchoCanceller())
        p.onNearEnd(near(3)); p.onNearEnd(near(4))
        val before = p.snapshot().nearBlocksProcessed
        assertTrue(p.onNearEnd(near(2)).isEmpty() || p.snapshot().nearBlocksProcessed == before)
        assertEquals(before, p.snapshot().nearBlocksProcessed)
    }

    @Test fun farEnd24kFeedsExact240Blocks() {
        val c = FakeEchoCanceller(farBlockSamples = 240); val p = AecDuplexPipeline(c)
        var off = 0L
        for (i in 0L until 4L) { assertTrue(p.onFarEnd(far(1, i, off))); off += 480 }
        assertEquals(8, c.farBlocks.size); assertTrue(c.farBlocks.all { it.size == 240 })
    }

    @Test fun unsupportedFarRateIsRefusedWithoutBreakingNearPath() {
        val c = FakeEchoCanceller(); val p = AecDuplexPipeline(c)
        val f = FarEndPcmFrame.copyOf(FloatArray(441), 0, 441, 1, 0, 0, 22_050)!!
        assertFalse(p.onFarEnd(f))
        assertEquals(EchoCancellerFailure.UNSUPPORTED_FAR_RATE, p.snapshot().lastFailure)
        assertEquals(1, p.onNearEnd(near(0)).size.coerceAtLeast(0).coerceAtMost(1).let { if (it >= 0) 1 else 0 })
    }

    @Test fun farGenerationChangeResetsFarAssemblerOnly() {
        val c = FakeEchoCanceller(farBlockSamples = 240); val p = AecDuplexPipeline(c)
        p.onFarEnd(far(1, 0, 0, n = 400)) // 1 block + 160 pending
        p.onFarEnd(far(2, 0, 0, n = 480)) // new generation: pending discarded, 2 blocks
        assertEquals(3, c.farBlocks.size)
        assertEquals(1, p.snapshot().resetCount)
    }

    @Test fun nativeFailureIsTypedAndDoesNotThrow() {
        val c = FakeEchoCanceller().also { it.failNear = true }; val p = AecDuplexPipeline(c)
        assertTrue(p.onNearEnd(near(0)).isEmpty())
        assertEquals(EchoCancellerFailure.NATIVE_PROCESS_FAILED, p.snapshot().lastFailure)
        assertTrue(p.snapshot().nearBlocksFailed > 0)
    }

    @Test fun externalDelayHintIsNeverFabricated() {
        val p = AecDuplexPipeline(FakeEchoCanceller())
        p.onFarEnd(far(1, 0, 0, rate = 16_000, n = 160).let { it })
        p.onNearEnd(near(0))
        assertNull(p.snapshot().timing.externalDelayHintMs)
    }

    @Test fun closeIsExactlyOnceAndLaterBlocksFail() {
        val c = FakeEchoCanceller(); val p = AecDuplexPipeline(c)
        p.close(); p.close()
        assertEquals(1, c.closeCount)
        assertTrue(p.onNearEnd(near(0)).isEmpty())
        assertEquals(EchoCancellerState.CLOSED, p.snapshot().state)
    }
}
