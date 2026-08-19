package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryEntryTest {

    @Test
    fun `entity triples default to empty, the reserved seam for a future graph`() {
        val entry = MemoryEntry(
            id = "1",
            content = "Il fissativo è a scaffale.",
            type = "fact",
            tier = MemoryTier.EPISODIC,
            timestamp = 1000L,
            importance = 0.5,
            source = "conversation",
            lastAccessed = 1000L,
        )
        assertTrue(entry.entityTriples.isEmpty())
    }

    @Test
    fun `all four tiers are distinct values`() {
        assertEquals(4, MemoryTier.entries.size)
        assertEquals(
            setOf("WORKING", "EPISODIC", "SEMANTIC", "USER"),
            MemoryTier.entries.map { it.name }.toSet(),
        )
    }
}
