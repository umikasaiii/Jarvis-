package com.simone.jarvismobile.tools

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CommandMatcherTest {
    @Test
    fun batteryLevelQuestionRunsBatteryTool() {
        val match = CommandMatcher.match("Quanto ho di batteria?")
        assertTrue(match is Match.Run)
        assertEquals("battery_status", (match as Match.Run).call.name)
    }

    @Test
    fun subjectlessChargingFollowUpUsesRecentBatteryContext() {
        val match = CommandMatcher.match(
            utterance = "È in carica in questo momento?",
            recentContext = "JARVIS: Batteria al 93 per cento.",
        )
        assertTrue(match is Match.Run)
        assertEquals("battery_status", (match as Match.Run).call.name)
    }

    @Test
    fun batteryStatementDoesNotRunATool() {
        assertNull(CommandMatcher.match("La batteria si sta caricando"))
    }

    @Test
    fun forgetCommandDeletesAMemoryByText() {
        val m = CommandMatcher.match("dimentica che mi piace il ketchup") as Match.Run
        assertEquals("forget_memory", m.call.name)
        assertEquals("mi piace il ketchup", m.call.arguments["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun deleteNoteCommandNeedsAMemoryCue() {
        val m = CommandMatcher.match("elimina l'appunto sul gelato") as Match.Run
        assertEquals("forget_memory", m.call.name)
        assertEquals("gelato", m.call.arguments["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun editCommandUpdatesAMemory() {
        val m = CommandMatcher.match("cambia l'appunto sul gelato in mi piace il cioccolato") as Match.Run
        assertEquals("update_memory", m.call.name)
        assertEquals("gelato", m.call.arguments["old"]?.jsonPrimitive?.content)
        assertEquals("mi piace il cioccolato", m.call.arguments["new"]?.jsonPrimitive?.content)
    }

    @Test
    fun recallQuestionsAreAnsweredFromSavedNotes() {
        // These must read the vault deterministically, never let the model invent.
        for (q in listOf(
            "Cosa ho salvato nei vault?",
            "Cosa mi piace?",
            "Che cibo mi piace?",
            "Cosa sai di me?",
        )) {
            val match = CommandMatcher.match(q)
            assertTrue("no match for: $q", match is Match.Run)
            assertEquals(q, "list_memories", (match as Match.Run).call.name)
        }
    }

    @Test
    fun shortenedAgendaFollowUpRunsAgendaTool() {
        val match = CommandMatcher.match("Ne ho uno in programma?")
        assertTrue(match is Match.Run)
        assertEquals("list_agenda", (match as Match.Run).call.name)
    }

    @Test
    fun supportedAppIsOpenedWithoutTheModel() {
        val match = CommandMatcher.match("Apri Google Maps") as Match.Run

        assertEquals("open_app", match.call.name)
        assertEquals("maps", match.call.arguments["app"]?.jsonPrimitive?.content)
    }

    @Test
    fun personalCalendarKeepsUserTitleAndStructuredDate() {
        val match = CommandMatcher.match(
            "Aggiungi al calendario dentista domani alle 15",
            now = LocalDateTime.of(2026, 8, 6, 10, 0),
        ) as Match.Run

        assertEquals("add_reminder", match.call.name)
        assertEquals("dentista", match.call.arguments["text"]?.jsonPrimitive?.content)
        assertEquals("2026-08-07", match.call.arguments["date"]?.jsonPrimitive?.content)
        assertEquals("15:00", match.call.arguments["time"]?.jsonPrimitive?.content)
    }

    @Test
    fun personalCalendarAsksInsteadOfGuessingTheDay() {
        val match = CommandMatcher.match("Crea un evento calendario per il dentista") as Match.Ask

        assertEquals("add_reminder", match.tool)
        assertEquals("when", match.missing)
        assertEquals("dentista", match.partial["text"])
    }

    @Test
    fun taskIntoNamedListBecomesAddTask() {
        val match = CommandMatcher.match("Aggiungi alla lista Lavoro comprare il pane") as Match.Run

        assertEquals("add_task", match.call.name)
        assertEquals("Lavoro", match.call.arguments["list"]?.jsonPrimitive?.content)
        assertEquals("Comprare il pane", match.call.arguments["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun specialTaskIsStarred() {
        val match = CommandMatcher.match("Attività speciale chiamare Mario") as Match.Run

        assertEquals("add_task", match.call.name)
        assertEquals("true", match.call.arguments["starred"]?.jsonPrimitive?.content)
        assertEquals("Chiamare mario", match.call.arguments["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun taskIntoListKeepsAnExplicitDay() {
        val match = CommandMatcher.match(
            "Nella lista Spesa il latte domani",
            now = LocalDateTime.of(2026, 8, 6, 10, 0),
        ) as Match.Run

        assertEquals("add_task", match.call.name)
        assertEquals("Spesa", match.call.arguments["list"]?.jsonPrimitive?.content)
        assertEquals("2026-08-07", match.call.arguments["date"]?.jsonPrimitive?.content)
    }

    @Test
    fun plainReminderIsNotHijackedByTheTaskPath() {
        // No list, no "speciale" → stays a reminder, not add_task.
        val match = CommandMatcher.match(
            "Ricordami di comprare il pane domani",
            now = LocalDateTime.of(2026, 8, 6, 10, 0),
        ) as Match.Run
        assertEquals("add_reminder", match.call.name)
    }

    @Test
    fun explicitGoogleCalendarRequestCreatesOnlyAnExternalDraft() {
        val match = CommandMatcher.match(
            "Esporta su Google Calendar dentista domani alle 15",
            now = LocalDateTime.of(2026, 8, 6, 10, 0),
        ) as Match.Run

        assertEquals("create_calendar_event", match.call.name)
        assertEquals("dentista", match.call.arguments["title"]?.jsonPrimitive?.content)
        assertEquals("2026-08-07", match.call.arguments["date"]?.jsonPrimitive?.content)
    }

    @Test
    fun untimedActivityBecomesAPersonalCalendarTask() {
        val match = CommandMatcher.match(
            "Aggiungi attività comprare il latte domani",
            now = LocalDateTime.of(2026, 8, 6, 10, 0),
        ) as Match.Run

        assertEquals("add_reminder", match.call.name)
        assertEquals("comprare il latte", match.call.arguments["text"]?.jsonPrimitive?.content)
        assertEquals("2026-08-07", match.call.arguments["date"]?.jsonPrimitive?.content)
        assertNull(match.call.arguments["time"])
    }

    @Test
    fun completedTaskUsesThePersonalCalendarTool() {
        val match = CommandMatcher.match("Segna comprare il latte come completato") as Match.Run

        assertEquals("complete_agenda", match.call.name)
        assertEquals("comprare il latte", match.call.arguments["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun contactsAreNotAnOpenableCapability() {
        assertNull(CommandMatcher.match("Apri i contatti"))
    }

    @Test
    fun smsIsAnEditableDraftWithExactNumberAndBody() {
        val match = CommandMatcher.match(
            "Prepara un SMS al +39 333 123 4567: arrivo tra poco",
        ) as Match.Run

        assertEquals("compose_sms", match.call.name)
        assertEquals("+393331234567", match.call.arguments["number"]?.jsonPrimitive?.content)
        assertEquals("arrivo tra poco", match.call.arguments["body"]?.jsonPrimitive?.content)
    }

    @Test
    fun callWithoutNumberAsksForIt() {
        val match = CommandMatcher.match("Chiama Marco") as Match.Ask

        assertEquals("prepare_call", match.tool)
        assertEquals("number", match.missing)
    }

    @Test
    fun notificationReadAndVaultSearchAreExplicit() {
        val notifications = CommandMatcher.match("Leggi le notifiche di WhatsApp") as Match.Run
        val vault = CommandMatcher.match("Cerca nel vault fattura moto") as Match.Run

        assertEquals("list_notifications", notifications.call.name)
        assertEquals("whatsapp", notifications.call.arguments["app"]?.jsonPrimitive?.content)
        assertEquals("search_vault", vault.call.name)
        assertEquals("fattura moto", vault.call.arguments["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun ordinaryPhrasesDoNotBecomeNavigationOrMusicActions() {
        assertNull(CommandMatcher.match("Vai a dormire presto"))
        assertNull(CommandMatcher.match("Portami il caffè"))
        assertNull(CommandMatcher.match("Metti la giacca blu"))
        assertNull(CommandMatcher.match("Metti in pausa il lavoro"))
        assertNull(CommandMatcher.match("Ascolta quello che ti dico"))
    }

    @Test
    fun callMeLaterRemainsACountdown() {
        val match = CommandMatcher.match("Chiamami tra 10 minuti") as Match.Run

        assertEquals("set_timer", match.call.name)
        assertEquals("600", match.call.arguments["seconds"]?.jsonPrimitive?.content)
    }
}
