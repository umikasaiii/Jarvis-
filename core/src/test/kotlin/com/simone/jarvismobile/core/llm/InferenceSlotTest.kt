package com.simone.jarvismobile.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOTFIX 3 — regression tests of the native-lifecycle contract. TEST KIND: JVM unit tests with a FAKE native
 * engine (no real model, no Android, no emulator). They prove the lifecycle logic; they do NOT prove that the
 * real LiteRT-LM library honours the contract assumed in [NativeConversation] (that is device-only).
 */
class InferenceSlotTest {

    // ---- fakes ----------------------------------------------------------------------------------------

    private class FakeConversation(val engine: FakeEngine, val id: Int) : NativeConversation {
        @Volatile var closed = false
        @Volatile var cancelled = false
        private val release = CountDownLatch(1)
        @Volatile var honourCancel = true

        override fun send(text: String): String {
            engine.sends.add(text)
            val running = engine.running.incrementAndGet()
            engine.maxRunning.set(maxOf(engine.maxRunning.get(), running))
            engine.sendStarted.countDown()
            try {
                if (closed) engine.useAfterClose.incrementAndGet()
                engine.script(this, text)?.let { return it }
                // Blocking mode: wait for release() or (if honoured) cancel().
                while (!release.await(5, TimeUnit.MILLISECONDS)) {
                    if (cancelled && honourCancel) throw IllegalStateException("native_cancelled")
                    if (closed) engine.useAfterClose.incrementAndGet()
                }
                return "reply-$id"
            } finally {
                engine.running.decrementAndGet()
            }
        }

        fun release() = release.countDown()
        override fun cancel() { cancelled = true; engine.cancels.incrementAndGet() }
        override fun close() {
            if (engine.running.get() > 0) engine.closedWhileRunning.incrementAndGet()
            closed = true
            engine.closes.incrementAndGet()
        }
    }

    private class FakeEngine : NativeEngine {
        val conversations = CopyOnWriteArrayList<FakeConversation>()
        val sends = CopyOnWriteArrayList<String>()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val useAfterClose = AtomicInteger()
        val closedWhileRunning = AtomicInteger()
        val closes = AtomicInteger()
        val cancels = AtomicInteger()
        @Volatile var sendStarted = CountDownLatch(1)
        @Volatile var engineClosed = false

        /** Returns an immediate reply, or null to block until released/cancelled. */
        @Volatile var script: (FakeConversation, String) -> String? = { _, _ -> null }

        override fun open(systemPrompt: String?): NativeConversation =
            FakeConversation(this, conversations.size + 1).also { conversations.add(it) }

        override fun close() { engineClosed = true }
        fun rearm() { sendStarted = CountDownLatch(1) }
    }

    private class ManualScheduler : InferenceScheduler {
        private class Item(val due: Long, val task: () -> Unit) { @Volatile var live = true }
        private val items = CopyOnWriteArrayList<Item>()
        @Volatile var clock = 0L
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val i = Item(clock + delayMs, task); items.add(i); return { i.live = false }
        }
        fun advance(ms: Long) {
            clock += ms
            items.filter { it.live && it.due <= clock }.forEach { it.live = false; it.task() }
        }
    }

    private class Rig(
        config: InferenceConfig = InferenceConfig(chatLockWaitMs = 400, onceLockWaitMs = 150, unwindGraceMs = 1_000),
    ) {
        val engine = FakeEngine()
        val scheduler = ManualScheduler()
        val log = InferenceEventLog()
        val wedgedCalls = AtomicInteger()
        val generating = CopyOnWriteArrayList<Boolean>()
        val slot = InferenceSlot(
            label = "fast", engine = engine, scheduler = scheduler, log = log, config = config,
            now = { scheduler.clock }, dispatcher = Dispatchers.IO,
            onGenerating = { generating.add(it) }, onWedged = { wedgedCalls.incrementAndGet() },
        )
        val scope = CoroutineScope(Dispatchers.Default + Job())
    }

    private fun awaitTrue(ms: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("condition not met in ${ms}ms")
            Thread.sleep(5)
        }
    }

    // ---- reset during generation ---------------------------------------------------------------------

    @Test fun `reset during a generation never closes the conversation that is in use`() = runBlocking {
        val r = Rig()
        val answer = r.scope.async { r.slot.chat("domanda", "sys", 90_000) }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        val epochBefore = r.slot.epoch
        r.slot.reset("new_conversation")
        // The native conversation is still in use: it must NOT have been closed yet.
        assertEquals(0, r.engine.closes.get())
        assertTrue(r.slot.epoch > epochBefore, "owners must see the reset immediately")
        r.engine.conversations[0].release()
        assertEquals("reply-1", answer.await())
        // After the call returned, the deferred reset is applied by the lane owner.
        awaitTrue { r.engine.closes.get() == 1 }
        assertEquals(0, r.engine.closedWhileRunning.get())
        assertEquals(0, r.engine.useAfterClose.get())
        // Next turn starts on a brand-new conversation.
        r.engine.script = { _, _ -> "nuova" }
        assertEquals("nuova", r.slot.chat("altra", "sys", 90_000))
        assertEquals(2, r.engine.conversations.size)
    }

    @Test fun `reset while idle closes the conversation immediately`() = runBlocking {
        val r = Rig()
        r.engine.script = { _, _ -> "ok" }
        r.slot.chat("a", "sys", 1000)
        r.slot.reset()
        assertEquals(1, r.engine.closes.get())
    }

    // ---- concurrency ------------------------------------------------------------------------------------

    @Test fun `classifier-style once() cannot run concurrently with a chat turn`() = runBlocking {
        val r = Rig()
        val chat = r.scope.async { r.slot.chat("lunga", "sys", 90_000) }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        // A helper call arrives while the user turn is generating: bounded wait, then null (no native entry).
        assertNull(r.slot.once("classifica", null, 5_000, "classifier"))
        assertEquals(1, r.engine.sends.size)
        r.engine.conversations[0].release()
        assertEquals("reply-1", chat.await())
        assertEquals(1, r.engine.maxRunning.get(), "native sends must be serialised")
        // The lane is free again: the helper now works.
        r.engine.script = { _, _ -> """{"tool":"none"}""" }
        assertNotNull(r.slot.once("classifica", null, 5_000, "classifier"))
        assertEquals(1, r.engine.maxRunning.get())
    }

    @Test fun `many concurrent callers never overlap in native code`() = runBlocking {
        val r = Rig(InferenceConfig(chatLockWaitMs = 5_000, onceLockWaitMs = 5_000))
        r.engine.script = { _, _ -> Thread.sleep(15); "ok" }
        val jobs = (1..12).map { i ->
            r.scope.async { if (i % 2 == 0) r.slot.chat("c$i", "sys", 90_000) else r.slot.once("o$i", null, 90_000) }
        }
        jobs.forEach { assertEquals("ok", it.await()) }
        assertEquals(1, r.engine.maxRunning.get())
        assertEquals(0, r.engine.useAfterClose.get())
    }

    // ---- timeout / cancel / reuse -----------------------------------------------------------------------

    @Test fun `timeout is followed by a clean new request on a fresh conversation`() = runBlocking {
        val r = Rig()
        val first = r.scope.async { runCatching { r.slot.chat("blocca", "sys", 90_000) } }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        val epoch = r.slot.epoch
        r.scheduler.advance(90_000) // watchdog fires -> native cancel
        val res = first.await()
        assertTrue(res.exceptionOrNull() is GenerationTimedOut, "got ${res.exceptionOrNull()}")
        assertTrue(r.engine.conversations[0].cancelled)
        assertTrue(r.engine.conversations[0].closed, "the poisoned conversation must be discarded")
        assertTrue(r.slot.epoch > epoch)
        r.engine.script = { _, _ -> "dopo-timeout" }
        assertEquals("dopo-timeout", r.slot.chat("ancora", "sys", 90_000))
        assertEquals(2, r.engine.conversations.size)
        assertFalse(r.slot.wedged)
    }

    @Test fun `cancel then reuse of the engine`() = runBlocking {
        val r = Rig()
        val first = r.scope.async { runCatching { r.slot.chat("blocca", "sys", 90_000) } }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        r.slot.cancel()
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertTrue(r.engine.conversations[0].closed)
        r.engine.script = { _, _ -> "riuso" }
        assertEquals("riuso", r.slot.chat("ok", "sys", 90_000))
        assertEquals(0, r.engine.closedWhileRunning.get())
    }

    @Test fun `a stop requested while a call is still queued is not lost`() = runBlocking {
        val r = Rig(InferenceConfig(chatLockWaitMs = 5_000, onceLockWaitMs = 5_000))
        val a = r.scope.async { runCatching { r.slot.chat("A", "sys", 90_000) } }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        val b = r.scope.async { runCatching { r.slot.chat("B", "sys", 90_000) } }
        delay(80) // let B queue for the lane
        r.slot.cancel()
        assertTrue(a.await().exceptionOrNull() is CancellationException)
        assertTrue(b.await().exceptionOrNull() is CancellationException, "queued call must abort, not start")
        assertEquals(listOf("A"), r.engine.sends.toList(), "B must never reach native code")
    }

    @Test fun `cancelling the caller scope reaches the native cancel and frees the lane`() = runBlocking {
        val r = Rig()
        val owner = Job()
        val scope = CoroutineScope(Dispatchers.Default + owner)
        val call = scope.launch { r.slot.chat("blocca", "sys", 90_000) }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        call.cancelAndJoin() // e.g. Activity/ViewModel scope destroyed
        assertTrue(r.engine.conversations[0].cancelled, "caller cancellation must stop native inference")
        awaitTrue { r.slot.isIdle }
        r.engine.script = { _, _ -> "libero" }
        assertEquals("libero", r.slot.chat("x", "sys", 90_000))
    }

    @Test fun `a generation owned by a worker scope survives the cancellation of an unrelated UI scope`() = runBlocking {
        val r = Rig()
        val workerScope = CoroutineScope(Dispatchers.Default + Job())
        val uiScope = CoroutineScope(Dispatchers.Default + Job())
        val answer = workerScope.async { r.slot.chat("domanda", "sys", 90_000) }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        uiScope.launch { delay(10_000) }
        (uiScope.coroutineContext[Job])!!.cancelAndJoin() // Activity goes away / background
        assertEquals(0, r.engine.cancels.get(), "UI teardown must not stop a worker-owned generation")
        r.engine.conversations[0].release()
        assertEquals("reply-1", answer.await())
    }

    // ---- wedge / shutdown --------------------------------------------------------------------------------

    @Test fun `native code that ignores cancel is reported as wedged after the grace period`() = runBlocking {
        val r = Rig()
        val job = r.scope.async { runCatching { r.slot.chat("blocca", "sys", 90_000) } }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        r.engine.conversations[0].honourCancel = false
        r.slot.cancel()
        assertEquals(0, r.wedgedCalls.get())
        r.scheduler.advance(1_000)
        assertEquals(1, r.wedgedCalls.get())
        assertTrue(r.slot.wedged)
        // A new request does not hang forever: it gets a bounded "busy".
        assertNull(r.slot.chat("nuova", "sys", 90_000))
        r.engine.conversations[0].release()
        job.await()
    }

    @Test fun `shutdown during a generation closes the engine only after native code returned`() = runBlocking {
        val r = Rig()
        val job = r.scope.async { runCatching { r.slot.chat("blocca", "sys", 90_000) } }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        r.slot.shutdown()
        // cancel was requested; the (fake) native call unwinds on its own, then the lane owner closes.
        job.await()
        assertTrue(r.slot.awaitClosed(2_000))
        assertTrue(r.engine.engineClosed)
        assertEquals(0, r.engine.closedWhileRunning.get())
        assertNull(r.slot.chat("dopo", "sys", 1_000))
    }

    @Test fun `shutdown while idle closes immediately`() = runBlocking {
        val r = Rig()
        r.slot.shutdown()
        assertTrue(r.engine.engineClosed)
        assertTrue(r.slot.awaitClosed(100))
    }

    // ---- degenerate output ------------------------------------------------------------------------------

    @Test fun `a degenerate reply is discarded and retried once on a fresh conversation`() = runBlocking {
        val r = Rig()
        r.engine.script = { conv, _ -> if (conv.id == 1) "212121" else "Parigi" }
        assertEquals("Parigi", r.slot.chat("capitale della Francia?", "sys", 90_000))
        assertEquals(2, r.engine.conversations.size)
        assertTrue(r.engine.conversations[0].closed, "the damaged conversation is not kept")
        val ev = r.log.snapshot().first { it.type == InferenceEventType.DEGENERATE }
        assertTrue(ev.tag.contains("PERIODIC_SHORT"), ev.tag)
        assertEquals(2, ev.n1) // unit length of the loop
        assertTrue(r.log.snapshot().any { it.type == InferenceEventType.DEGENERATE_RETRY })
    }

    @Test fun `degenerate twice returns null and leaves no conversation behind`() = runBlocking {
        val r = Rig()
        r.engine.script = { _, _ -> "212121" }
        val epoch = r.slot.epoch
        assertNull(r.slot.chat("domanda", "sys", 90_000))
        assertTrue(r.engine.conversations.all { it.closed })
        assertTrue(r.slot.epoch > epoch)
    }

    @Test fun `diagnostics identify the source of a degenerate reply without recording content`() = runBlocking {
        val r = Rig()
        r.engine.script = { conv, _ ->
            when (conv.id) { 1 -> "risposta ok"; 2 -> "212121"; else -> "recuperata" }
        }
        r.slot.chat("prima", "sys", 90_000)            // conversation 1, turn 1 fine
        r.slot.reset("nuova_conversazione")            // user taps «Nuova conversazione»
        r.slot.chat("SEGRETO-IBAN-IT60X", "sys", 90_000) // conversation 2 degenerates right after the reset
        val d = r.log.snapshot().first { it.type == InferenceEventType.DEGENERATE }
        assertTrue(d.tag.contains("from_reset"), "source must say it came right after a reset: ${d.tag}")
        val rendered = r.log.render(500)
        assertFalse(rendered.contains("SEGRETO"), "no conversation content in diagnostics")
        assertFalse(rendered.contains("212121"), "no model output in diagnostics")
    }

    // ---- misc ---------------------------------------------------------------------------------------------

    @Test fun `generating flag follows the native call`() = runBlocking {
        val r = Rig()
        r.engine.script = { _, _ -> "ok" }
        r.slot.chat("a", "sys", 1000)
        assertEquals(listOf(true, false), r.generating.toList())
    }

    @Test fun `native failure returns null and discards the conversation`() = runBlocking {
        val r = Rig()
        r.engine.script = { _, _ -> throw IllegalStateException("boom") }
        assertNull(r.slot.chat("a", "sys", 1000))
        assertTrue(r.engine.conversations[0].closed)
        r.engine.script = { _, _ -> "ripreso" }
        assertEquals("ripreso", r.slot.chat("b", "sys", 1000))
    }

    @Test fun `unexpected cancellation exception type is preserved for once()`() = runBlocking {
        val r = Rig()
        val job = r.scope.async { runCatching { r.slot.once("x", null, 90_000) } }
        assertTrue(r.engine.sendStarted.await(2, TimeUnit.SECONDS))
        r.slot.cancel()
        assertFailsWith<CancellationException> { job.await().getOrThrow() }
    }
}
