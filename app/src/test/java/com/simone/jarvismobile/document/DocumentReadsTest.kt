package com.simone.jarvismobile.document

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentReadsTest {
    @Test fun anEmptyResultStaysEmpty() {
        assertEquals(emptyList<String>(), readOrThrow("chunks") { emptyList<String>() })
    }

    @Test fun aDatabaseErrorIsNotAnEmptyResult() {
        val log = ArrayList<String>()
        try {
            readOrThrow("chunks", { log += it }) { throw IllegalStateException("database closed: contenuto privato") }
            fail("must not return an empty list")
        } catch (e: DocumentReadException) {
            assertEquals("chunks", e.what)
            assertTrue(e.cause is IllegalStateException)
        }
        assertEquals(listOf("document_read_failed chunks IllegalStateException"), log)
        assertTrue("no message text in the log", log.none { "privato" in it })
    }

    @Test fun cancellationPassesThroughUntouched() {
        try {
            readOrThrow("chunks") { throw CancellationException("stop") }
            fail()
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }
}
