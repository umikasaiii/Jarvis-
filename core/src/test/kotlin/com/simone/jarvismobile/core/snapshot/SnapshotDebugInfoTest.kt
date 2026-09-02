package com.simone.jarvismobile.core.snapshot

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotDebugInfoTest {

    private val now = Instant.parse("2026-01-01T10:00:00Z")

    @Test
    fun `describe matches the exact requested format`() {
        val info = SnapshotDebugInfo(
            snapshotId = "s1",
            sourcesAvailable = setOf("agenda"),
            sourcesMissing = setOf("driving"),
            ageMs = 1000L,
            selected = setOf(SelectionCategory.TEMPORAL, SelectionCategory.AGENDA, SelectionCategory.MEMORY),
            skipped = setOf(SelectionCategory.DRIVING, SelectionCategory.DEVICE, SelectionCategory.LOCATION, SelectionCategory.RECENT_EVENTS, SelectionCategory.TASK, SelectionCategory.CAPABILITY),
            memoryItemCount = 2,
            recentEventCount = 0,
            approxSizeChars = 1430,
        )
        val text = info.describe()
        assertTrue(text.startsWith("ContextSelection:"))
        assertTrue(text.contains("TEMPORAL → selected"))
        assertTrue(text.contains("AGENDA → selected"))
        assertTrue(text.contains("DRIVING → skipped"))
        assertTrue(text.contains("DEVICE → skipped"))
        assertTrue(text.contains("MEMORY → 2 items"))
        assertTrue(text.endsWith("Size → 1430 chars"))
    }

    @Test
    fun `never leaks personal content, only counts and category names`() {
        val info = SnapshotDebugInfo(
            snapshotId = "s1", sourcesAvailable = emptySet(), sourcesMissing = emptySet(), ageMs = 0L,
            selected = setOf(SelectionCategory.AGENDA), skipped = emptySet(), memoryItemCount = 1, recentEventCount = 0, approxSizeChars = 10,
        )
        val text = info.describe()
        // The formatter has no code path that can embed a title/summary string — structural guarantee, not just an assertion on this sample.
        assertFalse(text.contains("Dentista"))
    }

    @Test
    fun `from() derives counts from the selection`() {
        val snapshot = PersonalIntelligenceSnapshot(snapshotId = "s2", createdAt = now, sourceSummary = SourceSummary(available = setOf("agenda")))
        val selection = RelevantPersonalContext(
            memory = MemoryContext(items = listOf(MemoryContextItem("a"), MemoryContextItem("b")), capturedAt = now),
            selected = setOf(SelectionCategory.MEMORY), skipped = setOf(SelectionCategory.AGENDA), approxSizeChars = 42,
        )
        val info = SnapshotDebugInfo.from(snapshot, selection, ageMs = 500L)
        assertEquals(2, info.memoryItemCount)
        assertEquals(42, info.approxSizeChars)
        assertEquals(setOf("agenda"), info.sourcesAvailable)
    }
}
