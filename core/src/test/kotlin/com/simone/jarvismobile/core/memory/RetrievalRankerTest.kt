package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetrievalRankerTest {

    private val ranker = RetrievalRanker()

    private val chunks = listOf(
        MemoryChunk(
            notePath = "01-Memoria/hardware.md",
            title = "Preferenze Hardware PC",
            text = "Per il PC preferisco 96 GB di RAM e una GPU potente.",
            tags = listOf("hardware", "pc"),
            folder = "01-Memoria",
            ageDays = 2.0,
        ),
        MemoryChunk(
            notePath = "02-Progetti/casa.md",
            title = "Progetto Casa",
            text = "Ristrutturazione del bagno e nuova cucina.",
            tags = listOf("casa"),
            folder = "02-Progetti",
            ageDays = 40.0,
        ),
        MemoryChunk(
            notePath = "05-Conoscenza/cucina.md",
            title = "Ricette",
            text = "La pasta alla carbonara richiede guanciale.",
            tags = listOf("cucina"),
            folder = "05-Conoscenza",
            ageDays = 5.0,
        ),
    )

    @Test
    fun `ranks the topically relevant note first`() {
        val ranked = ranker.rank("quanta RAM preferisco per il PC", chunks)
        assertTrue(ranked.isNotEmpty())
        assertEquals("01-Memoria/hardware.md", ranked.first().chunk.notePath)
    }

    @Test
    fun `title and tag matches beat body only matches`() {
        val ranked = ranker.rank("progetto casa", chunks)
        assertEquals("02-Progetti/casa.md", ranked.first().chunk.notePath)
    }

    @Test
    fun `irrelevant query returns nothing`() {
        val ranked = ranker.rank("astronomia galassie", chunks)
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `empty query returns nothing`() {
        assertTrue(ranker.rank("   ", chunks).isEmpty())
    }

    @Test
    fun `respects the limit`() {
        val ranked = ranker.rank("pc casa cucina ram", chunks, limit = 2)
        assertTrue(ranked.size <= 2)
    }

    @Test
    fun `a synonym in the query still finds the note`() {
        val car = MemoryChunk(
            notePath = "garage.md",
            title = "Auto",
            text = "La mia automobile va in officina a marzo.",
            tags = listOf("mezzi"),
        )
        val ranked = ranker.rank("quando ho comprato la macchina", listOf(car))
        assertTrue(ranked.isNotEmpty())
        assertEquals("garage.md", ranked.first().chunk.notePath)
    }

    @Test
    fun `inflected query term matches the note`() {
        val note = MemoryChunk("gatti.md", "Animali", "Ho due gatti in casa.")
        val ranked = ranker.rank("come stanno i gatto", listOf(note))
        assertTrue(ranked.isNotEmpty())
    }

    @Test
    fun `recency breaks ties toward newer notes`() {
        val a = MemoryChunk("new.md", "Nota", "argomento comune progetto", ageDays = 1.0)
        val b = MemoryChunk("old.md", "Nota", "argomento comune progetto", ageDays = 300.0)
        val ranked = ranker.rank("argomento comune progetto", listOf(b, a))
        assertEquals("new.md", ranked.first().chunk.notePath)
    }
}
