package com.simone.jarvismobile.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestTraceTest {
    private fun t(i: Int, reason: String = "") = RequestTrace(i.toLong(), "FACTUAL", true, 2, 1, true, 3, "related", false, true, "abstain", listOf("personal_first"), 120, reason)

    @Test fun aTraceCarriesCountsAndCodesButNoFreeText() {
        val line = t(1, "Il codice del mio progetto è Orione!!").render()
        assertFalse(line.contains("Orione") || line.contains("codice del mio"), line)
        assertTrue(line.contains("mem=2/1") && line.contains("know=3:related") && line.contains("fb=personal_first") && line.contains("pub=abstain"), line)
        assertTrue(line.length < 260)
    }

    @Test fun theLogIsBoundedAndClearable() {
        val sink = ArrayList<String>(); val log = RequestTraceLog(capacity = 3) { sink += it }
        (1..5).forEach { log.record(t(it)) }
        assertEquals(listOf(3L, 4L, 5L), log.snapshot().map { it.atMs }); assertEquals(5, sink.size)
        log.clear(); assertTrue(log.snapshot().isEmpty()); assertEquals("(nessuna richiesta registrata)", log.render())
    }
}
