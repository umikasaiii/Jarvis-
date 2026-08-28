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
    fun `category survives the markdown round trip`() {
        val records = listOf(
            MemoryRecord(
                id = "mem-c",
                text = "Mi piace la pizza ai funghi",
                createdAt = 40,
                category = "Cibo e gusti",
            ),
        )
        assertEquals(records, MemoryRecordCodec.parse(MemoryRecordCodec.render(records)))
    }

    @Test
    fun `category normalize maps free answers to canonical buckets`() {
        assertEquals("Cibo e gusti", MemoryCategories.normalize("Cibo e gusti"))
        assertEquals("Cibo e gusti", MemoryCategories.normalize("cibo"))
        assertEquals("Cibo e gusti", MemoryCategories.normalize("Alimentazione."))
        assertEquals("Salute", MemoryCategories.normalize("medicina"))
        assertEquals("Altro", MemoryCategories.normalize("qualcosa di strano"))
        assertEquals("Altro", MemoryCategories.normalize(""))
    }

    @Test
    fun `list categories win over generic ones`() {
        assertEquals("Da comprare", MemoryCategories.normalize("Da comprare"))
        assertEquals("Da comprare", MemoryCategories.normalize("devo comprare il pane"))
        assertEquals("Da guardare", MemoryCategories.normalize("un film da guardare"))
        assertEquals("Da visitare", MemoryCategories.normalize("posto da visitare"))
        assertEquals("Da fare", MemoryCategories.normalize("cose da fare"))
        assertTrue(MemoryCategories.CANONICAL.containsAll(MemoryCategories.LISTS))
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
    fun `old paired format still parses, the echoed bullet is discarded not trusted`() {
        // Pre-existing files (saved before Obsidian sync was removed) pair a
        // JSON comment with a human-readable "- text" echo. The JSON is now
        // the sole source of truth, so hand-editing that echo line must have
        // no effect — it is simply consumed and dropped, never merged in.
        val original = MemoryRecord(
            id = "stable-id",
            text = "Preferisco il tè",
            kind = MemoryKind.SENSITIVE,
            createdAt = 10,
        )
        val legacyPaired = "<!-- jarvis-memory-v2:" +
            kotlinx.serialization.json.Json.encodeToString(MemoryRecord.serializer(), original) +
            " -->\n- 🔒 Venerdì incontro con Giulia\n\n"

        val parsed = MemoryRecordCodec.parse(legacyPaired).single()
        assertEquals("stable-id", parsed.id)
        assertEquals(MemoryKind.SENSITIVE, parsed.kind)
        assertEquals("Preferisco il tè", parsed.text)
    }

    @Test
    fun `multi-line text survives the round trip, no longer collapsed to one line`() {
        val records = listOf(
            MemoryRecord(
                id = "multi-line",
                text = "Riga uno\n\nRiga due con **grassetto**\n- punto a\n- punto b",
                createdAt = 50,
            ),
        )
        assertEquals(records, MemoryRecordCodec.parse(MemoryRecordCodec.render(records)))
    }

    @Test
    fun `theme survives the round trip and an unknown theme sanitizes to default`() {
        val records = listOf(
            MemoryRecord(id = "themed", text = "Nota a tema", createdAt = 60, theme = "ocean"),
        )
        assertEquals(records, MemoryRecordCodec.parse(MemoryRecordCodec.render(records)))

        val unknownThemeSaved = MemoryRecordCodec.render(records).replace("\"ocean\"", "\"tema-futuro\"")
        assertEquals("", MemoryRecordCodec.parse(unknownThemeSaved).single().theme)
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
