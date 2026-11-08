package com.simone.jarvismobile.core.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentFormatPolicyTest {
    @Test fun everyDeclaredFormatIsRecognised() {
        val expected = mapOf(
            "a.pdf" to DocumentKind.PDF, "a.docx" to DocumentKind.DOCX, "a.epub" to DocumentKind.EPUB, "a.xlsx" to DocumentKind.XLSX,
            "a.txt" to DocumentKind.TEXT, "a.md" to DocumentKind.MARKDOWN, "a.html" to DocumentKind.HTML,
            "a.csv" to DocumentKind.CSV, "a.json" to DocumentKind.JSON, "a.jpg" to DocumentKind.IMAGE, "a.PNG" to DocumentKind.IMAGE,
        )
        expected.forEach { (n, k) -> assertEquals(k, DocumentFormatPolicy.kindOf(n, "application/octet-stream"), n) }
    }

    @Test fun unsupportedFormatsAreRejectedNotIndexedAsText() {
        listOf("virus.exe", "song.mp3", "archive.zip", "video.mp4").forEach {
            assertEquals(ImportFailure.UNSUPPORTED, DocumentFormatPolicy.checkMetadata(it, "application/octet-stream", 100), it)
        }
    }

    @Test fun anOversizedFileIsRefusedFromItsDeclaredSizeAlone() {
        assertEquals(ImportFailure.TOO_LARGE, DocumentFormatPolicy.checkMetadata("a.pdf", "application/pdf", DocumentFormatPolicy.MAX_FILE_BYTES + 1))
        assertNull(DocumentFormatPolicy.checkMetadata("a.pdf", "application/pdf", DocumentFormatPolicy.MAX_FILE_BYTES))
        assertNull(DocumentFormatPolicy.checkMetadata("a.pdf", "application/pdf", 0)) // size unknown
    }

    @Test fun theLimitIsDeclaredAs25MB() {
        assertEquals(25L * 1024 * 1024, DocumentFormatPolicy.MAX_FILE_BYTES)
        assertTrue("25 MB" in ImportFailure.TOO_LARGE.message)
    }

    @Test fun emptyFileIsRejected() =
        assertEquals(ImportFailure.EMPTY, DocumentFormatPolicy.checkBytes(DocumentKind.TEXT, ByteArray(0)))

    @Test fun aPdfThatIsNotAPdfIsCorrupt() {
        assertEquals(ImportFailure.CORRUPT, DocumentFormatPolicy.checkBytes(DocumentKind.PDF, "hello".toByteArray()))
        assertNull(DocumentFormatPolicy.checkBytes(DocumentKind.PDF, "%PDF-1.7\n...".toByteArray()))
    }

    @Test fun anOfficeFileThatIsNotAZipIsCorrupt() {
        assertEquals(ImportFailure.CORRUPT, DocumentFormatPolicy.checkBytes(DocumentKind.DOCX, "not a zip".toByteArray()))
        assertNull(DocumentFormatPolicy.checkBytes(DocumentKind.XLSX, DocumentRagTestKit.zip("a.xml" to "<x/>")))
    }

    @Test fun aBinaryBlobNamedTxtIsNotIndexedAsText() {
        val blob = ByteArray(200) { if (it % 7 == 0) 0 else 65 }
        assertEquals(ImportFailure.CORRUPT, DocumentFormatPolicy.checkBytes(DocumentKind.TEXT, blob))
    }

    @Test fun plainUtf8TextPasses() =
        assertNull(DocumentFormatPolicy.checkBytes(DocumentKind.TEXT, "Ciao è tutto".toByteArray()))

    @Test fun unknownExtensionWithTextMimeIsAccepted() =
        assertEquals(DocumentKind.TEXT, DocumentFormatPolicy.kindOf("notes.weird", "text/plain"))
}
