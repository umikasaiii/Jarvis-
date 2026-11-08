package com.simone.jarvismobile.core.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteCommandParserTest {
    @Test fun createNamed() =
        assertEquals(NoteCommand.CreateNamed("Idee per Jarvis", ""), NoteCommandParser.parse("Crea una nota chiamata Idee per Jarvis."))

    @Test fun createNamedWithContent() =
        assertEquals(NoteCommand.CreateNamed("Moto", "cambiare olio"), NoteCommandParser.parse("crea una nota intitolata Moto: cambiare olio"))

    @Test fun appendWithColon() =
        assertEquals(NoteCommand.Append("Idee per Jarvis", "integrare Wikipedia offline"), NoteCommandParser.parse("Aggiungi alla nota Idee per Jarvis: integrare Wikipedia offline"))

    @Test fun appendInline() =
        assertEquals(NoteCommand.Append("Spesa", "latte"), NoteCommandParser.parse("aggiungi latte alla nota Spesa"))

    @Test fun showNotesAbout() =
        assertEquals(NoteCommand.Show("moto"), NoteCommandParser.parse("Mostrami le note sulla moto"))

    @Test fun showNotesAboutTopicWithApostrophe() =
        assertEquals(NoteCommand.Show("assicurazione"), NoteCommandParser.parse("cerca le note sull'assicurazione"))

    @Test fun plainReminderIsNotANoteCommand() {
        assertNull(NoteCommandParser.parse("Ricordami domani alle 18 di controllare l'assicurazione"))
        assertNull(NoteCommandParser.parse("aggiungi comprare il latte alla lista Spesa"))
    }

    @Test fun createWithoutNameIsNotNamed() =
        assertNull(NoteCommandParser.parse("crea una nota"))
}
