package com.simone.jarvismobile.core.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source that yields [total] frames (1ms apart, like a blocking read) then keeps going / errors. */
private class FakeSource(
    private val total: Int,
    private val failAfter: Boolean = false,
    private val startOk: Boolean = true,
    override val sampleRateHz: Int = 16_000,
) : PcmSource {
    override val description = "fake"
    val releases = AtomicInteger()
    val stops = AtomicInteger()
    private val reads = AtomicInteger()
    val onFirstRead = CompletableDeferred<Unit>()
    override fun start() = startOk
    override fun read(dest: ShortArray, count: Int): Int {
        val i = reads.getAndIncrement()
        if (i == 0) onFirstRead.complete(Unit)
        Thread.sleep(1)
        if (i >= total) return if (failAfter) -1 else count.also { fill(dest, count, i) }
        fill(dest, count, i)
        return count
    }
    private fun fill(d: ShortArray, c: Int, i: Int) { for (k in 0 until c) d[k] = (i * 100 + 1000).toShort() }
    override fun stop() { stops.incrementAndGet() }
    override fun release() { releases.incrementAndGet() }
}

private class Factory(private val result: () -> PcmSourceOpenResult) : PcmSourceFactory {
    val opens = AtomicInteger()
    override fun open(): PcmSourceOpenResult { opens.incrementAndGet(); return result() }
}

class PcmCaptureEngineTest {
    private fun engine(f: PcmSourceFactory) = PcmCaptureEngine(f, Dispatchers.Default, frameSamples = 64)

    @Test fun permissionDeniedIsTypedAndOpensNothing() = runBlocking {
        val f = Factory { PcmSourceOpenResult.Failed(VoiceCaptureFailure.PERMISSION_DENIED, "permission_denied") }
        val o = engine(f).run(50)
        assertEquals(VoiceCaptureFailure.PERMISSION_DENIED, o.failure)
    }

    @Test fun initAndStartFailuresAreTypedAndSourceReleasedOnce() = runBlocking {
        val bad = Factory { PcmSourceOpenResult.Failed(VoiceCaptureFailure.INITIALIZATION_FAILED, "x") }
        assertEquals(VoiceCaptureFailure.INITIALIZATION_FAILED, engine(bad).run(50).failure)
        val src = FakeSource(10, startOk = false)
        val o = engine(Factory { PcmSourceOpenResult.Opened(src) }).run(50)
        assertEquals(VoiceCaptureFailure.START_FAILED, o.failure)
        assertEquals(1, src.releases.get())
    }

    @Test fun readFailureIsTyped() = runBlocking {
        val src = FakeSource(3, failAfter = true)
        val o = engine(Factory { PcmSourceOpenResult.Opened(src) }).run(2_000)
        assertEquals(VoiceCaptureFailure.READ_FAILED, o.failure)
        assertEquals(1, src.releases.get())
    }

    @Test fun fixedWindowCompletesFansOutInOrderAndMicLevelComesFromFrames() = runBlocking {
        val src = FakeSource(1_000_000)
        val e = engine(Factory { PcmSourceOpenResult.Opened(src) })
        val got = mutableListOf<VoicePcmFrame>()
        val ready = CompletableDeferred<Unit>()
        val c1 = launch(Dispatchers.Default) { e.frames.onSubscription { ready.complete(Unit) }.collect { got.add(it) } }
        ready.await()
        val o = e.run(60)
        assertTrue(o.completed)
        c1.cancel()
        assertTrue(got.size >= 3, "frames=${got.size}")
        assertEquals(0L, got.first().sequence)
        assertEquals(got.indices.map { it.toLong() }, got.map { it.sequence }) // monotonic, gapless to this fast consumer
        assertEquals(0f, e.micLevel.value) // reset after run
        assertEquals(VoiceCaptureState.IDLE, e.snapshot.value.state)
        assertTrue(e.snapshot.value.framesRead >= 3)
        assertEquals(64, e.snapshot.value.lastFrameSamples)
        assertEquals(1, src.releases.get())
    }

    @Test fun replayIsZeroLateSubscriberSeesNothingOld() = runBlocking {
        val src = FakeSource(1_000_000)
        val e = engine(Factory { PcmSourceOpenResult.Opened(src) })
        e.run(30)
        val late = mutableListOf<VoicePcmFrame>()
        val job = launch(Dispatchers.Default) { e.frames.collect { late.add(it) } }
        kotlinx.coroutines.delay(30)
        job.cancel()
        assertTrue(late.isEmpty())
    }

    @Test fun slowConsumerNeverBlocksCaptureAndDropsAreCounted() = runBlocking {
        val src = FakeSource(1_000_000)
        val e = PcmCaptureEngine(Factory { PcmSourceOpenResult.Opened(src) }, Dispatchers.Default, frameSamples = 64)
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = launch(Dispatchers.Default) {
            e.frames.onSubscription { ready.complete(Unit) }.collect { release.await() } // stuck consumer
        }
        ready.await()
        val o = withTimeout(5_000) { e.run(400) } // must still finish: capture never waits on the consumer
        assertTrue(o.completed)
        assertTrue(e.snapshot.value.framesDropped > 0, "dropped=${e.snapshot.value.framesDropped}")
        slow.cancel()
    }

    @Test fun cancelDuringCaptureStopsAndReleasesOnce() = runBlocking {
        val src = FakeSource(1_000_000)
        val e = engine(Factory { PcmSourceOpenResult.Opened(src) })
        val d = async(Dispatchers.Default) { e.run(null) }
        src.onFirstRead.await()
        e.cancel()
        val o = withTimeout(5_000) { d.await() }
        assertEquals(VoiceCaptureFailure.CANCELLED, o.failure)
        e.cancel(); e.cancel() // repeated cancel is harmless
        assertEquals(1, src.releases.get())
        assertEquals(1, src.stops.get())
    }

    @Test fun cancelBeforeStartIsNoOpAndRestartWorks() = runBlocking {
        val src = FakeSource(1_000_000)
        val e = engine(Factory { PcmSourceOpenResult.Opened(src) })
        e.cancel() // idle: no-op, must not poison the next run
        assertTrue(e.run(30).completed)
    }

    @Test fun restartAfterCancelUsesFreshGenerationAndSequenceFromZero() = runBlocking {
        val sources = java.util.concurrent.CopyOnWriteArrayList<FakeSource>()
        val e = engine(Factory { FakeSource(1_000_000).also { sources.add(it) }.let { PcmSourceOpenResult.Opened(it) } })
        val first = async(Dispatchers.Default) { e.run(null) }
        while (sources.isEmpty()) kotlinx.coroutines.delay(1)
        sources[0].onFirstRead.await()
        val gen1 = e.snapshot.value.generation
        e.cancel(); first.await()
        val seqs = mutableListOf<Long>()
        val ready = CompletableDeferred<Unit>()
        val c = launch(Dispatchers.Default) { e.frames.onSubscription { ready.complete(Unit) }.collect { seqs.add(it.sequence) } }
        ready.await()
        assertTrue(e.run(40).completed)
        c.cancel()
        assertTrue(e.snapshot.value.generation > gen1)
        assertEquals(0L, seqs.first())
        assertEquals(1, sources[0].releases.get()); assertEquals(1, sources[1].releases.get())
    }

    @Test fun staleGenerationFramesAreNeverEmittedAfterCancel() = runBlocking {
        val src = FakeSource(1_000_000)
        val e = engine(Factory { PcmSourceOpenResult.Opened(src) })
        val seen = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val c = launch(Dispatchers.Default) { e.frames.onSubscription { ready.complete(Unit) }.collect { seen.incrementAndGet() } }
        ready.await()
        val d = async(Dispatchers.Default) { e.run(null) }
        src.onFirstRead.await()
        e.cancel(); d.await()
        val after = seen.get()
        kotlinx.coroutines.delay(30)
        assertEquals(after, seen.get(), "no frame after the run ended")
        c.cancel()
    }

    @Test fun secondConcurrentRunIsAlreadyOwnedAndOpensNoSecondRecorder() = runBlocking {
        val src = FakeSource(1_000_000)
        val f = Factory { PcmSourceOpenResult.Opened(src) }
        val e = engine(f)
        val d = async(Dispatchers.Default) { e.run(null) }
        src.onFirstRead.await()
        val second = e.run(10)
        assertEquals(VoiceCaptureFailure.ALREADY_OWNED, second.failure)
        assertEquals(1, f.opens.get())
        e.cancel(); d.await()
    }

    @Test fun consumerOrOwnerCoroutineCancellationReleasesRecorder() = runBlocking {
        val src = FakeSource(1_000_000)
        val e = engine(Factory { PcmSourceOpenResult.Opened(src) })
        val job = launch(Dispatchers.Default) { e.run(null) }
        src.onFirstRead.await()
        job.cancel(); job.join()
        assertEquals(1, src.releases.get())
        assertEquals(VoiceCaptureState.IDLE, e.snapshot.value.state)
        assertTrue(e.run(20).completed) // can be owned again
    }
}
