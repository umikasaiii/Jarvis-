package com.simone.jarvismobile.core.document

import com.simone.jarvismobile.core.document.DocumentRagTestKit.record
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DocumentResolverTest {
    private val docs = listOf(
        record("1", "Manuale moto.pdf", importedAt = 10),
        record("2", "Assicurazione 2025.pdf", importedAt = 20),
        record("3", "Assicurazione 2026.pdf", importedAt = 30),
        record("4", "Ricevuta.pdf", DocumentStatus.FAILED, importedAt = 40),
    )

    @Test fun latestIsTheNewestReadyDocumentNotAFailedOne() =
        assertEquals("3", (DocumentResolver.resolve(DocRef.Latest, docs) as DocResolution.Found).record.id)

    @Test fun namedMatchesByWords() =
        assertEquals("1", (DocumentResolver.resolve(DocRef.Named("manuale della moto"), docs) as DocResolution.Found).record.id)

    @Test fun twoMatchesAreAmbiguousAndNeverGuessed() {
        val r = DocumentResolver.resolve(DocRef.Named("assicurazione"), docs)
        assertEquals(setOf("2", "3"), (r as DocResolution.Ambiguous).candidates.map { it.id }.toSet())
    }

    @Test fun moreSpecificNameResolves() =
        assertEquals("2", (DocumentResolver.resolve(DocRef.Named("assicurazione 2025"), docs) as DocResolution.Found).record.id)

    @Test fun unknownNameIsNotFound() = assertIs<DocResolution.NotFound>(DocumentResolver.resolve(DocRef.Named("bolletta"), docs))
    @Test fun failedDocumentsAreNotSearchable() = assertIs<DocResolution.NotFound>(DocumentResolver.resolve(DocRef.Named("ricevuta"), docs))
    @Test fun noDocumentsAtAll() = assertIs<DocResolution.NoDocuments>(DocumentResolver.resolve(DocRef.Latest, emptyList()))
}
