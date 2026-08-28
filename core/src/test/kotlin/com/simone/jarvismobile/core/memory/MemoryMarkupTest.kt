package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryMarkupTest {
    @Test
    fun `bold italic underline strip markers and record a styled run`() {
        val bold = MemoryMarkup.parse("Ciao **mondo**").single()
        assertEquals("Ciao mondo", bold.text)
        assertEquals(listOf(StyledRun(5, 10, InlineStyle.Bold)), bold.runs)

        val italic = MemoryMarkup.parse("Ciao *mondo*").single()
        assertEquals("Ciao mondo", italic.text)
        assertEquals(listOf(StyledRun(5, 10, InlineStyle.Italic)), italic.runs)

        val underline = MemoryMarkup.parse("Ciao <u>mondo</u>").single()
        assertEquals("Ciao mondo", underline.text)
        assertEquals(listOf(StyledRun(5, 10, InlineStyle.Underline)), underline.runs)
    }

    @Test
    fun `default and colored highlight, text color, font size`() {
        val defaultHl = MemoryMarkup.parse("==importante==").single()
        assertEquals("importante", defaultHl.text)
        assertEquals(InlineStyle.Highlight("#FFEB3B"), defaultHl.runs.single().style)

        val coloredHl = MemoryMarkup.parse("[hl=#FF00AA]rosa[/hl]").single()
        assertEquals("rosa", coloredHl.text)
        assertEquals(InlineStyle.Highlight("#FF00AA"), coloredHl.runs.single().style)

        val color = MemoryMarkup.parse("[color=#00FF00]verde[/color]").single()
        assertEquals("verde", color.text)
        assertEquals(InlineStyle.TextColor("#00FF00"), color.runs.single().style)

        val size = MemoryMarkup.parse("[size=xl]grande[/size]").single()
        assertEquals("grande", size.text)
        assertEquals(InlineStyle.FontSize(SizeStep.EXTRA_LARGE), size.runs.single().style)
    }

    @Test
    fun `multiple non-overlapping styles on the same line keep correct offsets`() {
        val line = MemoryMarkup.parse("**uno** normale *due*").single()
        assertEquals("uno normale due", line.text)
        assertEquals(
            listOf(
                StyledRun(0, 3, InlineStyle.Bold),
                StyledRun(12, 15, InlineStyle.Italic),
            ),
            line.runs,
        )
    }

    @Test
    fun `block markers are recognised per line and stripped from text`() {
        val lines = MemoryMarkup.parse(
            listOf(
                "# Titolo",
                "## Sottotitolo",
                "- punto elenco",
                "1. primo",
                "- [ ] da fare",
                "- [x] fatto",
                "---",
                "testo normale",
            ).joinToString("\n"),
        )
        assertEquals("Titolo", lines[0].text); assertTrue(lines[0].isHeading1)
        assertEquals("Sottotitolo", lines[1].text); assertTrue(lines[1].isHeading2)
        assertEquals("punto elenco", lines[2].text); assertTrue(lines[2].isBullet)
        assertEquals("primo", lines[3].text); assertTrue(lines[3].isNumbered)
        assertEquals("da fare", lines[4].text); assertEquals(false, lines[4].isChecklistChecked)
        assertEquals("fatto", lines[5].text); assertEquals(true, lines[5].isChecklistChecked)
        assertTrue(lines[6].isDivider)
        assertNull(lines[7].isChecklistChecked)
    }

    @Test
    fun `alignment prefix is stripped and reported`() {
        val center = MemoryMarkup.parse("[center]testo centrato").single()
        assertEquals("testo centrato", center.text)
        assertEquals(MarkupAlign.CENTER, center.align)

        val right = MemoryMarkup.parse("[right]testo a destra").single()
        assertEquals("testo a destra", right.text)
        assertEquals(MarkupAlign.END, right.align)

        val start = MemoryMarkup.parse("testo normale").single()
        assertEquals(MarkupAlign.START, start.align)
    }

    @Test
    fun `plainText strips every marker for compact previews`() {
        val raw = "# Titolo\n**grassetto** e ==evidenziato== con [color=#FF0000]colore[/color]"
        assertEquals("Titolo grassetto e evidenziato con colore", MemoryMarkup.plainText(raw))
    }

    @Test
    fun `unstyled text is untouched`() {
        val line = MemoryMarkup.parse("Solo testo semplice, niente markup.").single()
        assertEquals("Solo testo semplice, niente markup.", line.text)
        assertTrue(line.runs.isEmpty())
    }
}
