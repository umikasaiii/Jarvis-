package com.simone.jarvismobile.core.chat

import com.simone.jarvismobile.core.llm.GenerationTimedOut
import com.simone.jarvismobile.core.llm.InferenceConfig
import com.simone.jarvismobile.core.llm.InferenceEventLog
import com.simone.jarvismobile.core.llm.InferenceEventType
import com.simone.jarvismobile.core.llm.InferenceScheduler
import com.simone.jarvismobile.core.llm.InferenceSlot
import com.simone.jarvismobile.core.llm.NativeConversation
import com.simone.jarvismobile.core.llm.NativeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HOTFIX 4 — end-to-end regression of "every answer belongs to its question".
 *
 * TEST KIND: JVM, FAKE native engine whose reply is a pure function of the text it was sent ("R(<text>)"), so a
 * reply that lands under the wrong question is detectable. The turn pipeline is the real [ChatTurnRunner] +
 * [TurnLedger] + [InferenceSlot]. This proves the orchestration/commit logic; it is NOT evidence about what the
 * real Gemma/LiteRT-LM model generates.
 */
class ChatTurnRunnerTest {

    private class FakeConv(val engine: FakeEng) : NativeConversation {
        @Volatile var cancelled = false
        override fun send(text: String): String {
            engine.sent.add(text)
            if (text.startsWith("BLOCK")) {
                while (!cancelled) Thread.sleep(2)
                throw IllegalStateException("native_cancelled")
            }
            if (text.startsWith("SLOW")) Thread.sleep(40)
            return "R($text)"
        }
        override fun cancel() { cancelled = true }
        override fun close() {}
    }

    private class FakeEng : NativeEngine {
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var started = CountDownLatch(1)
        override fun open(systemPrompt: String?): NativeConversation = FakeConv(this)
        override fun close() {}
    }

    private class Clock : InferenceScheduler {
        private class Item(val due: Long, val task: () -> Unit) { @Volatile var live = true }
        private val items = CopyOnWriteArrayList<Item>()
        @Volatile var now = 0L
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit { val i = Item(now + delayMs, task); items.add(i); return { i.live = false } }
        fun advance(ms: Long) { now += ms; items.filter { it.live && it.due <= now }.forEach { it.live = false; it.task() } }
    }

    private data class Line(val user: Boolean, val text: String, val taskId: String?)

    private class Sim {
        val engine = FakeEng()
        val clock = Clock()
        val log = InferenceEventLog()
        val chat = CopyOnWriteArrayList<Line>()
        val slot = InferenceSlot(
            "fast", engine, clock, log,
            InferenceConfig(chatLockWaitMs = 30_000, onceLockWaitMs = 30_000, unwindGraceMs = 60_000),
            now = { clock.now }, dispatcher = Dispatchers.IO,
        )
        val ledger = TurnLedger(log, now = { clock.now })
        val runner = ChatTurnRunner(
            Mutex(), ledger,
            ChatSink { user, text, id ->
                synchronized(chat) { if (id == null || chat.none { it.user == user && it.taskId == id }) chat.add(Line(user, text, id)) }
            },
            { slot.epoch },
        )

        suspend fun ask(id: String, order: Long, text: String, timeoutMs: Long = 90_000): Result<TurnResult> =
            runCatching { runner.run(id, order, text) { q -> slot.chat(q, "sys", timeoutMs) ?: error("no_answer") } }

        /** The Stop button: ledger first (synchronous), then the native stop. */
        fun stop() { ledger.stopAll(); slot.cancel() }

        /** «Nuova conversazione»: invalidate, abandon what runs, reset the native state, clear the chat. */
        fun newConversation() {
            ledger.newConversation(); slot.cancel(); slot.reset("new_conversation"); chat.clear()
        }

        /** Every answer line must be R(question) of the SAME task, directly after that question. */
        fun assertAssociation() {
            val lines = chat.toList()
            lines.filter { !it.user }.forEach { a ->
                val q = lines.firstOrNull { it.user && it.taskId == a.taskId }
                assertNotNull(q, "answer ${a.text} has no question line for task ${a.taskId}")
                assertEquals("R(${q.text})", a.text, "answer under the wrong question")
                assertEquals(lines.indexOf(q) + 1, lines.indexOf(a), "answer is not directly after its question")
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private fun awaitTrue(ms: Long = 3000, c: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!c()) { if (System.currentTimeMillis() > end) error("condition not met"); Thread.sleep(4) }
    }

    // 1 ----------------------------------------------------------------------------------------------------
    @Test fun `ten consecutive questions keep every answer with its own question`() = runBlocking {
        val s = Sim()
        val qs = listOf(
            "Che lingua si parla in Italia?", "Cos'è un SSD?", "Quanto è grande l'Asia?", "Dimmi un piatto tipico italiano",
            "Qual è la capitale della Francia?", "Quanti giorni ha febbraio?", "Cos'è la RAM?", "Chi ha scritto la Divina Commedia?",
            "Quanto fa 12 per 12?", "Che ore sono a Tokyo?",
        )
        qs.forEachIndexed { i, q ->
            val r = s.ask("t$i", i.toLong(), q).getOrThrow()
            assertEquals(TurnResult.Answered("R($q)"), r)
        }
        assertEquals(20, s.chat.size)
        s.assertAssociation()
        // The native conversation is reused (memory works): one conversation, ten sends.
        assertEquals(10, s.engine.sent.size)
    }

    // 2 ----------------------------------------------------------------------------------------------------
    @Test fun `two questions sent in quick succession are answered in order and not mixed`() = runBlocking {
        val s = Sim()
        val a = scope.async { s.ask("a", 1, "SLOW Asia?") }
        delay(5)
        val b = scope.async { s.ask("b", 2, "SLOW Piatto?") }
        assertTrue(a.await().getOrThrow() is TurnResult.Answered)
        assertTrue(b.await().getOrThrow() is TurnResult.Answered)
        assertEquals(listOf("SLOW Asia?", "R(SLOW Asia?)", "SLOW Piatto?", "R(SLOW Piatto?)"), s.chat.map { it.text })
        s.assertAssociation()
    }

    // 3 ----------------------------------------------------------------------------------------------------
    @Test fun `stop during generation then a new question gives only the new answer`() = runBlocking {
        val s = Sim()
        val first = scope.async { s.ask("t1", 1, "BLOCK lingua") }
        awaitTrue { s.engine.sent.contains("BLOCK lingua") }
        s.stop()
        val r1 = first.await()
        assertTrue(r1.exceptionOrNull() is CancellationException || r1.getOrNull() is TurnResult.Discarded, "got $r1")
        val r2 = s.ask("t2", 2, "Quanto è grande l'Asia?").getOrThrow()
        assertEquals(TurnResult.Answered("R(Quanto è grande l'Asia?)"), r2)
        assertFalse(s.chat.any { !it.user && it.taskId == "t1" }, "the stopped question must have no answer")
        s.assertAssociation()
    }

    @Test fun `stop in the instant the model finishes still discards that answer`() = runBlocking {
        val s = Sim()
        val r = runCatching {
            s.runner.run("t1", 1, "domanda") { q ->
                val answer = s.slot.chat(q, "sys", 90_000)!!
                s.stop() // Stop tapped after native returned, before the commit
                answer
            }
        }.getOrThrow()
        assertEquals(TurnResult.Discarded(TurnLedger.Reason.STOPPED), r)
        assertFalse(s.chat.any { !it.user }, "no late answer may appear after Stop")
        assertTrue(s.log.snapshot().any { it.type == InferenceEventType.TURN_DISCARD && it.tag.startsWith("STOPPED") })
    }

    // 4 ----------------------------------------------------------------------------------------------------
    @Test fun `new conversation during generation leaves no trace of the old turn`() = runBlocking {
        val s = Sim()
        val old = scope.async { s.ask("old", 1, "BLOCK vecchia domanda") }
        awaitTrue { s.engine.sent.contains("BLOCK vecchia domanda") }
        s.newConversation()
        old.await() // unwinds (cancelled) — either way nothing may be written
        assertEquals(emptyList(), s.chat.toList(), "old turn must not write into the fresh chat")
        val r = s.ask("new", 2, "Dimmi un piatto tipico italiano").getOrThrow()
        assertEquals(TurnResult.Answered("R(Dimmi un piatto tipico italiano)"), r)
        assertEquals(listOf("Dimmi un piatto tipico italiano", "R(Dimmi un piatto tipico italiano)"), s.chat.map { it.text })
    }

    @Test fun `new conversation in the instant the model finishes discards the finished old answer`() = runBlocking {
        val s = Sim()
        val r = s.runner.run("old", 1, "vecchia") { q ->
            val a = s.slot.chat(q, "sys", 90_000)!!
            s.newConversation()
            a
        }
        assertEquals(TurnResult.Discarded(TurnLedger.Reason.CONVERSATION_RESET), r)
        assertEquals(emptyList(), s.chat.toList())
    }

    // 5 ----------------------------------------------------------------------------------------------------
    @Test fun `process restart with queued requests answers each once, in order, without duplicates`() = runBlocking {
        val tasks = listOf("t1" to "Che lingua?", "t2" to "Cos'è un SSD?", "t3" to "Quanto è grande l'Asia?")
        // Before the "death": t1 was fully answered and persisted; t2 and t3 never ran.
        val persisted = listOf(Line(true, "Che lingua?", "t1"), Line(false, "R(Che lingua?)", "t1"))
        val s = Sim()
        s.chat.addAll(persisted)
        // After restart WorkManager re-runs every unfinished task (and even t1: it must be idempotent).
        tasks.forEachIndexed { i, (id, text) ->
            val existing = Transcript.existingReply(s.chat.map { TranscriptLine(it.user, it.text, it.taskId) }, id)
            if (existing == null) s.ask(id, i.toLong(), text).getOrThrow()
        }
        assertEquals(6, s.chat.size)
        assertEquals(listOf("t1", "t1", "t2", "t2", "t3", "t3"), s.chat.map { it.taskId })
        s.assertAssociation()
    }

    @Test fun `a late retry of an older message after newer answers is dropped not shown out of order`() = runBlocking {
        val s = Sim()
        s.ask("q-old", 100, "Che lingua si parla in Italia?") // first attempt failed earlier; simulate: never answered
        s.chat.clear() // (the row exists but its first attempt produced nothing)
        s.ask("q2", 200, "Quanto è grande l'Asia?").getOrThrow()
        s.ask("q3", 300, "Dimmi un piatto tipico italiano").getOrThrow()
        val late = s.ask("q-old", 100, "Che lingua si parla in Italia?").getOrThrow()
        assertEquals(TurnResult.Discarded(TurnLedger.Reason.SUPERSEDED), late)
        assertFalse(s.chat.any { it.taskId == "q-old" })
        s.assertAssociation()
    }

    // 6 ----------------------------------------------------------------------------------------------------
    @Test fun `timeout is reported then the next question is answered on a clean conversation`() = runBlocking {
        val s = Sim()
        val first = scope.async { s.ask("t1", 1, "BLOCK lenta", timeoutMs = 90_000) }
        awaitTrue { s.engine.sent.contains("BLOCK lenta") }
        s.clock.advance(90_000)
        assertTrue(first.await().exceptionOrNull() is GenerationTimedOut)
        assertFalse(s.chat.any { !it.user }, "a timed-out turn writes no answer")
        val r = s.ask("t2", 2, "Dimmi un piatto tipico italiano").getOrThrow()
        assertEquals(TurnResult.Answered("R(Dimmi un piatto tipico italiano)"), r)
        s.assertAssociation()
    }

    // 7 ----------------------------------------------------------------------------------------------------
    @Test fun `randomised mix of questions stops and resets never attaches an answer to the wrong question`() = runBlocking {
        repeat(5) { seed ->
            val s = Sim()
            val rnd = Random(seed)
            val running = mutableListOf<kotlinx.coroutines.Deferred<Result<TurnResult>>>()
            for (i in 0 until 40) {
                when (rnd.nextInt(10)) {
                    0 -> s.stop()
                    1 -> s.newConversation()
                    else -> {
                        val prefix = if (rnd.nextInt(4) == 0) "BLOCK " else if (rnd.nextBoolean()) "SLOW " else ""
                        running += scope.async { s.ask("s$seed-$i", (seed * 1000 + i).toLong(), "$prefix" + "domanda $seed-$i") }
                    }
                }
                delay(rnd.nextLong(0, 6))
                // BLOCK turns only end through stop/new conversation: release them periodically.
                if (i % 7 == 6) s.stop()
            }
            s.stop()
            withTimeoutOrFail { running.awaitAll() }
            s.assertAssociation()
            // No task may have more than one answer or user line.
            s.chat.groupBy { it.user to it.taskId }.values.forEach { assertEquals(1, it.size) }
        }
    }

    private suspend fun <T> withTimeoutOrFail(block: suspend () -> T): T =
        kotlinx.coroutines.withTimeout(20_000) { block() }

    @Test fun `a stopped turn that waits behind another never runs`() = runBlocking {
        val s = Sim()
        val a = scope.async { s.ask("a", 1, "BLOCK a") }
        awaitTrue { s.engine.sent.contains("BLOCK a") }
        val b = scope.async { s.ask("b", 2, "domanda b") }
        delay(30) // b is queued on the lane
        s.stop()
        a.await()
        val rb = b.await()
        assertTrue(rb.getOrNull() is TurnResult.Discarded || rb.exceptionOrNull() is CancellationException, "got $rb")
        assertFalse(s.engine.sent.contains("domanda b"), "a stopped queued turn must never reach the engine")
    }
}
