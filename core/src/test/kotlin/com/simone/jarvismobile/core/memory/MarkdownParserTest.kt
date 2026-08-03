package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownParserTest {

    @Test
    fun `parses frontmatter title tags and links`() {
        val raw = """
            ---
            title: Preferenze Hardware
            tags: [hardware, pc]
            date: 2026-08-01
            ---
            # Preferenze Hardware

            Per il PC preferisco 96 GB di RAM. Vedi anche [[Progetto Casa]].
            Categoria #memoria #preferenze
        """.trimIndent()

        val note = MarkdownParser.parse("01-Memoria/pref.md", raw)
        assertEquals("Preferenze Hardware", note.title)
        assertEquals("2026-08-01", note.frontmatter["date"])
        assertTrue(note.tags.containsAll(listOf("hardware", "pc", "memoria", "preferenze")))
        assertEquals(listOf("Progetto Casa"), note.wikiLinks)
        assertTrue(note.body.contains("96 GB"))
    }

    @Test
    fun `parses yaml list style tags`() {
        val raw = """
            ---
            tags:
              - progetto
              - casa
            ---
            Contenuto.
        """.trimIndent()
        val note = MarkdownParser.parse("x.md", raw)
        assertTrue(note.tags.containsAll(listOf("progetto", "casa")))
    }

    @Test
    fun `falls back to first heading when no title in frontmatter`() {
        val note = MarkdownParser.parse("y.md", "# Titolo Dal Heading\n\ntesto")
        assertEquals("Titolo Dal Heading", note.title)
    }

    @Test
    fun `falls back to filename when no heading`() {
        val note = MarkdownParser.parse("99-Inbox/nota-rapida.md", "solo testo senza titolo")
        assertEquals("nota-rapida", note.title)
    }

    @Test
    fun `handles wiki link with alias`() {
        val note = MarkdownParser.parse("z.md", "vedi [[Progetto Casa|la casa]]")
        assertEquals(listOf("Progetto Casa"), note.wikiLinks)
    }

    @Test
    fun `note without frontmatter still parses`() {
        val note = MarkdownParser.parse("a.md", "# Ciao\ntesto #tag1")
        assertTrue(note.frontmatter.isEmpty())
        assertTrue(note.tags.contains("tag1"))
    }
}
