package com.simone.jarvismobile.core.document

import com.simone.jarvismobile.core.document.DocumentRagTestKit.record
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportFailureTest {
    @Test fun reasonRoundTripsThroughTags() {
        ImportFailure.entries.forEach { f ->
            assertEquals(f, ImportFailure.fromTags(listOf("folder:Casa", f.tag)))
        }
        assertNull(ImportFailure.fromTags(listOf("folder:Casa")))
    }

    @Test fun withReasonReplacesTheOldReasonAndKeepsOtherTags() {
        val t = ImportFailure.withReason(listOf("folder:A", ImportFailure.EMPTY.tag), ImportFailure.CORRUPT)
        assertEquals(listOf("folder:A", ImportFailure.CORRUPT.tag), t)
        assertEquals(listOf("folder:A"), ImportFailure.withReason(t, null))
    }

    @Test fun scannedPdfMessageSaysOcrIsNeededAndTheFileIsKept() {
        val m = ImportFailure.SCANNED_PDF.message
        assertTrue("OCR" in m && "conservato" in m)
    }

    @Test fun halfDoneImportsBecomeFailedInterruptedUnlessStillRunning() {
        val rs = listOf(
            record("a", "a.pdf", DocumentStatus.INDEXING), record("b", "b.pdf", DocumentStatus.PARSING),
            record("c", "c.pdf", DocumentStatus.READY), record("d", "d.pdf", DocumentStatus.FAILED),
        )
        val fixed = ImportRecovery.interrupted(rs, activeIds = setOf("b"))
        assertEquals(listOf("a"), fixed.map { it.id })
        assertEquals(DocumentStatus.FAILED, fixed.single().status)
        assertEquals(ImportFailure.INTERRUPTED, ImportFailure.fromTags(fixed.single().tags))
    }
}
