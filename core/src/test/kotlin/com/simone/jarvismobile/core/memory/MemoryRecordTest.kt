package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryRecordTest {
    @Test
    fun `markdown round trip keeps stable structured records`() {
        val records = listOf(
            MemoryRecord(
                id = "mem-a",
                text = "Preferisco allenarmi con Marco il venerdì",
                kind = MemoryKind.PERMANENT,
                createdAt = 10,
                updatedAt = 20,
                topics = listOf("allenarmi"),
                people = listOf("Marco"),
                dates = listOf("venerdì"),
            ),
            MemoryRecord(
                id = "mem-b",
                text = "Terapia medica aggiornata",
                kind = MemoryKind.SENSITIVE,
                createdAt = 30,
            ),
        )

        assertEquals(records, MemoryRecordCodec.parse(MemoryRecordCodec.render(records)))
    }

    @Test
    fun `legacy lines get deterministic ids and structured fields`() {
        val raw = """
            # Memoria di JARVIS

            - [2026-08-05 21:56] Venerdì appuntamento con Marco
        """.trimIndent()

        val first = MemoryRecordCodec.parse(raw).single()
        val second = MemoryRecordCodec.parse(raw).single()
        assertEquals(first.id, second.id)
        assertEquals("Venerdì appuntamento con Marco", first.text)
        assertEquals(listOf("Marco"), first.people)
        assertTrue(first.dates.contains("venerdì"))
    }

    @Test
    fun `direct Obsidian edits keep id and refresh searchable fields`() {
        val original = MemoryRecord(
            id = "stable-id",
            text = "Preferisco il tè",
            kind = MemoryKind.SENSITIVE,
            createdAt = 10,
        )
        val editedMarkdown = MemoryRecordCodec.render(listOf(original))
            .replace("🔒 Preferisco il tè", "🔒 Venerdì incontro con Giulia")

        val edited = MemoryRecordCodec.parse(editedMarkdown).single()
        assertEquals("stable-id", edited.id)
        assertEquals(MemoryKind.SENSITIVE, edited.kind)
        assertEquals("Venerdì incontro con Giulia", edited.text)
        assertTrue(edited.people.contains("Giulia"))
        assertTrue(edited.dates.contains("venerdì"))
    }

    @Test
    fun `classifier marks sensitive data and rejects credentials`() {
        assertEquals(MemoryKind.SENSITIVE, MemoryStructure.classify("La mia terapia medica è cambiata"))
        assertEquals(MemoryKind.TEMPORARY, MemoryStructure.classify("Tienilo solo per questa conversazione"))
        assertEquals(MemoryKind.PERMANENT, MemoryStructure.classify("Preferisco il tè verde"))
        assertTrue(MemoryStructure.containsCredential("La password è hunter2"))
        assertFalse(MemoryStructure.containsCredential("Preferisco il tè verde"))
    }

    @Test
    fun `short term summary is bounded structured and excludes questions and secrets`() {
        val turns = buildList {
            add(MemoryTurn(true, "Domani mi alleno con Marco e uso gli anelli"))
            add(MemoryTurn(false, "Va bene."))
            add(MemoryTurn(true, "La password è segretissima"))
            add(MemoryTurn(true, "Che ore sono?"))
            repeat(12) { add(MemoryTurn(it % 2 == 0, "messaggio recente $it")) }
        }

        val summary = ShortTermMemorySummarizer.summarize(turns, now = 42)
        assertEquals(42, summary.updatedAt)
        assertEquals(listOf("Domani mi alleno con Marco e uso gli anelli"), summary.facts)
        assertTrue(summary.people.contains("Marco"))
        assertTrue(summary.dates.contains("domani"))
        assertFalse(summary.forPrompt().contains("password", ignoreCase = true))
    }
}
