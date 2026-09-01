package com.simone.jarvismobile.core.archive

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveItemTest {

    private fun note(title: String = "Raspberry Pi", content: String = "GPIO 17 per il relè") = ArchiveItem(
        id = "1",
        kind = ArchiveKind.NOTE,
        title = title,
        content = content,
        tags = listOf("elettronica"),
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun blankTitleIsRejected() {
        assertFailsWith<IllegalArgumentException> { note(title = "  ") }
    }

    @Test
    fun memoryChunkCarriesTitleAndContent() {
        val chunk = note().toMemoryChunk()
        assertTrue(chunk.text.contains("Raspberry Pi"))
        assertTrue(chunk.text.contains("GPIO 17"))
        assertTrue(chunk.tags.contains("elettronica"))
    }

    @Test
    fun themeAndSpacingDefaultToBlank() {
        val item = note()
        assertTrue(item.theme.isEmpty())
        assertTrue(item.spacing.isEmpty())
    }

    @Test
    fun themeAndSpacingRoundTripThroughCopy() {
        val item = note().copy(theme = "sunset", spacing = "wide")
        assertTrue(item.theme == "sunset")
        assertTrue(item.spacing == "wide")
    }

    @Test
    fun toWatchChunkCarriesTypeAndLink() {
        val item = ArchiveItem(
            id = "2",
            kind = ArchiveKind.TO_WATCH,
            title = "Dune",
            watchType = "film",
            link = "https://example.invalid/dune",
            createdAt = 0L,
            updatedAt = 0L,
        )
        val chunk = item.toMemoryChunk()
        assertTrue(chunk.text.contains("film"))
        assertTrue(chunk.text.contains("dune", ignoreCase = true))
        assertTrue(chunk.folder == "to_watch")
    }
}
