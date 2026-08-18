package com.simone.jarvismobile.core.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArchiveLinkTest {

    @Test
    fun selfLinkIsRejected() {
        val ref = ArchiveRef("note", "1")
        assertFailsWith<IllegalArgumentException> {
            ArchiveLink(id = "l1", from = ref, to = ref, createdAt = 0L)
        }
    }

    @Test
    fun linkConnectsTwoDifferentEntities() {
        val link = ArchiveLink(
            id = "l1",
            from = ArchiveRef("note", "1"),
            to = ArchiveRef("document", "doc1"),
            createdAt = 0L,
        )
        assertEquals("note", link.from.type)
        assertEquals("document", link.to.type)
    }
}
