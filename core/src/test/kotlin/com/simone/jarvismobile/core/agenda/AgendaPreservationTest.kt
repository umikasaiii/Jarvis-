package com.simone.jarvismobile.core.agenda

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BETA 4 — agenda.md must never lose the text around the tasks. */
class AgendaPreservationTest {
    private val day = LocalDate.of(2026, 11, 10)
    private val a = AgendaEntry(date = day, text = "Chiamare il medico")
    private val b = AgendaEntry(date = null, text = "Comprare il pane", list = "Spesa")

    private val userFile = """
        # Agenda di JARVIS

        Promemoria personali, NON cancellare.
        ## Idee
        riga libera scritta in Obsidian
        - [ ] checklist a mano non riconosciuta

        ${a.toMarkdown()}
        ${b.toMarkdown()}

        > citazione finale
    """.trimIndent() + "\n"

    @Test fun theOldRenderDropsFreeText_reproduction() {
        val lost = Agenda.foreignLines(userFile)
        assertTrue(lost.isNotEmpty())
        val rewritten = Agenda.renderFile(Agenda.parseFile(userFile))
        // Every one of these lines used to disappear on the first edit of any task.
        listOf("Promemoria personali, NON cancellare.", "## Idee", "riga libera scritta in Obsidian", "> citazione finale")
            .forEach { assertTrue(it !in rewritten, "expected '$it' to be lost by the old renderFile") }
    }

    @Test fun preservingRenderKeepsEveryForeignLine() {
        val edited = Agenda.parseFile(userFile).map { if (it.text.startsWith("Chiamare")) it.copy(done = true, completedAt = day) else it }
        val out = Agenda.renderPreserving(userFile, edited)
        Agenda.foreignLines(userFile).forEach { assertTrue(it in out.lines(), "lost: $it") }
        assertTrue(Agenda.parseFile(out).single { it.text.startsWith("Chiamare") }.done)
    }

    @Test fun preservingRenderKeepsHeaderOnTopAndLinesInOrder() {
        val out = Agenda.renderPreserving(userFile, Agenda.parseFile(userFile))
        assertEquals("# Agenda di JARVIS", out.lines().first())
        assertEquals(Agenda.foreignLines(userFile), Agenda.foreignLines(out))
    }

    @Test fun reRenderingIsStable() {
        val once = Agenda.renderPreserving(userFile, Agenda.parseFile(userFile))
        val twice = Agenda.renderPreserving(once, Agenda.parseFile(once))
        assertEquals(once, twice)
    }

    @Test fun addingATaskDoesNotDuplicateOthers() {
        val c = AgendaEntry(date = day, text = "Nuova")
        val out = Agenda.renderPreserving(userFile, Agenda.parseFile(userFile) + c)
        assertEquals(3, Agenda.parseFile(out).size)
    }

    @Test fun deletingTheLastTaskStillKeepsTheText() {
        val out = Agenda.renderPreserving(userFile, emptyList())
        assertEquals(0, Agenda.parseFile(out).size)
        Agenda.foreignLines(userFile).forEach { assertTrue(it in out.lines()) }
    }

    @Test fun fileWithNoTasksYetGetsThemAppendedAfterTheText() {
        val text = "# Agenda di JARVIS\n\nnote mie\n"
        val out = Agenda.renderPreserving(text, listOf(a))
        assertTrue(out.startsWith("# Agenda di JARVIS"))
        assertTrue("note mie" in out)
        assertEquals(1, Agenda.parseFile(out).size)
    }

    @Test fun blankOrMissingFileGivesTheStandardFile() {
        assertEquals(Agenda.renderFile(listOf(a)), Agenda.renderPreserving("", listOf(a)))
        assertEquals(Agenda.renderFile(listOf(a)), Agenda.renderPreserving("  \n", listOf(a)))
    }

    @Test fun aFileWrittenByTheOldAppStaysReadable() {
        val old = Agenda.renderFile(listOf(a, b))
        val out = Agenda.renderPreserving(old, listOf(a, b))
        assertEquals(old.trimEnd(), out.trimEnd())
    }

    @Test fun windowsLineEndingsDoNotLoseTasks() {
        val crlf = userFile.replace("\n", "\r\n")
        val out = Agenda.renderPreserving(crlf, Agenda.parseFile(crlf))
        assertEquals(2, Agenda.parseFile(out).size)
        assertTrue("riga libera scritta in Obsidian" in out.replace("\r", ""))
    }
}
