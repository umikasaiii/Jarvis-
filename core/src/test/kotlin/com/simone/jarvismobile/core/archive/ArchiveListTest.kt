package com.simone.jarvismobile.core.archive

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveListTest {

    @Test
    fun blankListNameIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ArchiveList(id = "1", name = " ", type = ArchiveListType.CUSTOM, createdAt = 0L, updatedAt = 0L)
        }
    }

    @Test
    fun blankItemTitleIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ArchiveListItem(id = "1", listId = "l1", title = "", createdAt = 0L, updatedAt = 0L)
        }
    }

    @Test
    fun memoryChunkCarriesTitleListNameAndDescription() {
        val item = ArchiveListItem(
            id = "1",
            listId = "l1",
            title = "Filtro olio",
            description = "compatibile Honda",
            quantity = 2,
            createdAt = 0L,
            updatedAt = 0L,
        )
        val chunk = item.toMemoryChunk("Ricambi moto")
        assertTrue(chunk.text.contains("Filtro olio"))
        assertTrue(chunk.text.contains("Ricambi moto"))
        assertTrue(chunk.text.contains("compatibile Honda"))
        assertTrue(chunk.folder == "list:Ricambi moto")
    }
}
