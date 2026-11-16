package com.simone.jarvismobile.core.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3TokenTest {
    private class Conv(val count: () -> Int?) : NativeConversation {
        @Volatile var closed = false; val countAfterClose = AtomicInteger()
        override fun send(text: String) = "risposta"
        override fun cancel() {}
        override fun close() { closed = true }
        override fun tokenCount(): Int? { if (closed) countAfterClose.incrementAndGet(); return count() }
    }
    private class Eng(val count: () -> Int?) : NativeEngine {
        val convs = java.util.concurrent.CopyOnWriteArrayList<Conv>()
        override fun open(systemPrompt: String?, seed: ChatSeed): NativeConversation = Conv(count).also { convs += it }
        override fun close() {}
    }
    private val sched = InferenceScheduler { _, _ -> {} }
    private fun slot(e: Eng, w: Int? = 4096) = InferenceSlot("fast", e, sched, InferenceEventLog(), configuredWindow = w)

    @Test fun aMeasurementIsTiedToItsRequestSlotEpochAndWindow() = runBlocking {
        val e = Eng { 321 }; val s = slot(e)
        val ledger = TokenLedger("req-1")
        withContext(TokenLedgerContext(ledger)) { s.chat("ciao", "sys", 5_000) }
        val m = ledger.latest()!!
        assertEquals("req-1", m.requestId); assertEquals("fast", m.slot); assertEquals(321, m.tokens)
        assertEquals(4096, m.configuredWindow); assertEquals(1, m.callIndex); assertTrue(m.measured)
        assertEquals(TokenMeasurement.Source.CONVERSATION_COUNT, m.source)
        assertEquals(0, e.convs.sumOf { it.countAfterClose.get() }, "never read after close")
    }

    @Test fun aFailedOrMissingMeasurementLeavesTheRequestWithoutOne() = runBlocking {
        val ledger = TokenLedger("r")
        withContext(TokenLedgerContext(ledger)) { slot(Eng { throw IllegalStateException("jni") }).chat("a", "s", 5_000) }
        assertNull(ledger.latest())
        val l2 = TokenLedger("r2")
        withContext(TokenLedgerContext(l2)) { slot(Eng { null }).chat("a", "s", 5_000) }
        assertNull(l2.latest())
        // no ledger installed at all: the call works and nothing is recorded anywhere
        assertEquals("risposta", slot(Eng { 5 }).chat("a", "s", 5_000))
    }

    @Test fun concurrentRequestsEachKeepTheirOwnFigures() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val ledgers = (1..12).map { TokenLedger("q$it") }
        val e = Eng { 100 }
        ledgers.map { l -> scope.async { withContext(TokenLedgerContext(l)) { slot(e).once("x", "s", 5_000) } } }.awaitAll()
        assertTrue(ledgers.all { l -> l.all().size == 1 && l.all().single().requestId == l.requestId })
    }

    @Test fun twoGenerationsOfOneRequestAreBothCountedInOrderAndTheLedgerIsBounded() = runBlocking {
        val l = TokenLedger("r", capacity = 2); val s = slot(Eng { 7 })
        withContext(TokenLedgerContext(l)) { repeat(3) { s.once("x", "s", 5_000) } }
        assertEquals(listOf(1, 2), l.all().map { it.callIndex })
    }

    @Test fun windowCheckNeverAssumesAUniversalWindow() {
        assertFalse(ContextWindowCheck.check(null, 4096).consistent)
        assertTrue(ContextWindowCheck.check(4096, 4096).consistent)
        assertTrue("SMALLER" in ContextWindowCheck.check(2048, 4096).note)
        assertEquals(8192, ContextWindowCheck.check(8192, 4096).configured)
    }
}
