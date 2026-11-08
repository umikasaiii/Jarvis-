package com.simone.jarvismobile.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** BETA 2/8 — consecutive messages, ordering, duplicate protection, recovery after a process restart. */
class TranscriptTest {
    private fun fill(n: Int): List<TranscriptLine> {
        var t = emptyList<TranscriptLine>()
        for (i in 1..n) {
            t = Transcript.append(t, true, "domanda $i", "t$i")
            t = Transcript.append(t, false, "risposta $i", "t$i")
        }
        return t
    }

    @Test fun tenConsecutiveExchangesKeepArrivalOrder() {
        val t = fill(10)
        assertEquals(20, t.size)
        assertEquals((1..10).flatMap { listOf("domanda $it", "risposta $it") }, t.map { it.text })
        assertEquals(List(10) { listOf(true, false) }.flatten(), t.map { it.fromUser })
    }

    @Test fun aRetriedTaskDoesNotDuplicateTheUserLineOrTheReply() {
        var t = Transcript.append(emptyList(), true, "ciao", "t1")
        t = Transcript.append(t, true, "ciao", "t1")         // WorkManager retry re-appends the user line
        t = Transcript.append(t, false, "salve", "t1")
        t = Transcript.append(t, false, "salve!", "t1")      // second attempt produced another reply
        assertEquals(listOf("ciao", "salve"), t.map { it.text })
    }

    @Test fun theSameWordsAreAllowedTwiceWhenTheyAreDifferentMessages() {
        var t = Transcript.append(emptyList(), true, "ok", "t1")
        t = Transcript.append(t, true, "ok", "t2")
        assertEquals(2, t.size)
    }

    @Test fun blankInputIsIgnoredAndTheListIsUntouched() {
        val t = fill(1)
        assertSame(t, Transcript.append(t, true, "   ", "x"))
    }

    @Test fun recoveryAfterProcessRestart() {
        // The process died after the answer was saved: the restored log must hand the answer back, not regenerate.
        val restored = fill(2)
        assertEquals("risposta 2", Transcript.existingReply(restored, "t2"))
        // Died after saving only the user line: no reply yet, the user line must not be added again.
        val half = Transcript.append(emptyList(), true, "domanda 3", "t3")
        assertNull(Transcript.existingReply(half, "t3"))
        assertSame(half, Transcript.append(half, true, "domanda 3", "t3"))
    }

    @Test fun conversationLinesWithoutTaskIdAreNeverDeduplicated() {
        var t = Transcript.append(emptyList(), true, "sì")
        t = Transcript.append(t, true, "sì")
        assertEquals(2, t.size)
    }
}
