package com.simone.jarvismobile.core.chat

import com.simone.jarvismobile.core.llm.InferenceEventLog
import com.simone.jarvismobile.core.llm.InferenceEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnLedgerTest {
    @Test fun `a fresh turn may run and commit`() {
        val l = TurnLedger()
        val t = l.begin("a", 1, 0)
        assertNull(l.check(t))
        l.commit(t, 0)
        assertEquals(0, l.activeCount)
    }

    @Test fun `stop rejects a turn even if the model finished in the same instant`() {
        val l = TurnLedger()
        val t = l.begin("a", 1, 0)
        l.stopAll()
        assertEquals(TurnLedger.Reason.STOPPED, l.check(t))
    }

    @Test fun `a turn begun after stop is not affected`() {
        val l = TurnLedger()
        l.stopAll()
        assertNull(l.check(l.begin("b", 2, 0)))
    }

    @Test fun `stop by task id only touches that task`() {
        val l = TurnLedger()
        val a = l.begin("a", 1, 0)
        val b = l.begin("b", 2, 0)
        assertTrue(l.stop("a"))
        assertFalse(l.stop("zzz"))
        assertEquals(TurnLedger.Reason.STOPPED, l.check(a))
        assertNull(l.check(b))
    }

    @Test fun `new conversation invalidates turns in flight and only those`() {
        val l = TurnLedger()
        val old = l.begin("old", 1, 0)
        l.newConversation()
        val fresh = l.begin("new", 2, 0)
        assertEquals(TurnLedger.Reason.CONVERSATION_RESET, l.check(old))
        assertNull(l.check(fresh))
    }

    @Test fun `a late retry of an older message is superseded by a newer committed answer`() {
        val l = TurnLedger()
        val q1 = l.begin("q1", 100, 0)
        val q2 = l.begin("q2", 200, 0)
        l.commit(q2, 0)
        assertEquals(TurnLedger.Reason.SUPERSEDED, l.check(q1))
    }

    @Test fun `ordering restarts after a new conversation`() {
        val l = TurnLedger()
        l.commit(l.begin("q2", 200, 0), 0)
        l.newConversation()
        assertNull(l.check(l.begin("q1", 100, 0)))
    }

    @Test fun `diagnostics identify turn epoch engine state and discard reason without content`() {
        val log = InferenceEventLog()
        val l = TurnLedger(log, now = { 5 })
        val t = l.begin("task-secret-text-123", 1, 7)
        l.stopAll()
        l.discard(t, l.check(t)!!, 7)
        val ev = log.snapshot()
        assertTrue(ev.any { it.type == InferenceEventType.TURN_BEGIN && it.n1 == 1L && it.tag == "eng7" })
        val d = ev.single { it.type == InferenceEventType.TURN_DISCARD }
        assertEquals("STOPPED_eng7", d.tag)
        assertEquals(1L, d.n1)
        assertFalse(log.render().contains("secret"), "task id text must not be logged, only its hash")
    }
}
