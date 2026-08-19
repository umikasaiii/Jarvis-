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
    fun preferenceStatementIsAMemoryNotAStarredTask() {
        val m = CommandMatcher.match("Ricorda che la mia pizza preferita è la boscaiola") as Match.Run
        assertEquals("remember", m.call.name)
        assertTrue(m.call.arguments["text"]?.jsonPrimitive?.content?.contains("boscaiola") == true)
    }

    @Test
    fun explicitSpecialeStillMakesAStarredTask() {
        val m = CommandMatcher.match("aggiungi attività speciale chiamare Luca") as Match.Run
        assertEquals("add_task", m.call.name)
        assertEquals("true", m.call.arguments["starred"]?.jsonPrimitive?.content)
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
    fun cheHoDaFareIsUnderstoodLikeCosaHoDaFare() {
        // Real device bug: "che" as the colloquial "cosa" was only wired for
        // "che impegni/appuntamenti…", not "che ho da fare"/"che devo fare".
        for (q in listOf("Che ho da fare domani?", "Che devo fare?")) {
            val match = CommandMatcher.match(q)
            assertTrue("no match for: $q", match is Match.Run)
            assertEquals(q, "list_agenda", (match as Match.Run).call.name)
        }
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
    fun reminderTitleDoesNotKeepTheDanglingConnectiveFromAMidSentenceDate() {
        // Real device bug: "domani alle 21" sits between "ricordami" and the verb,
        // so the leftover "di" must not become part of the saved title.
        val match = CommandMatcher.match(
            "Ricordami domani alle 21 di sistemare tubo cameretta e lasciare chiavi della cantina",
            now = LocalDateTime.of(2026, 8, 6, 10, 0),
        ) as Match.Run

        assertEquals("add_reminder", match.call.name)
        assertEquals(
            "sistemare tubo cameretta e lasciare chiavi della cantina",
            match.call.arguments["text"]?.jsonPrimitive?.content,
        )
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
        // No list, no "speciale" → stays a reminder, not add_task. Deliberately
        // avoids "comprare" here: "ricordami di comprare X" is its own documented
        // behavior change (§34, docs/PERSONAL_ARCHIVE.md) that routes to the
        // shopping list instead — see addingAnItemToTheShoppingListWorksThroughSeveralPhrasings.
        val match = CommandMatcher.match(
            "Ricordami di chiamare il dottore domani",
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

    // "Segna X come completato" is handled entirely by the structured
    // AgendaIntentRouter path now (see AgendaCommandParserTest's COMPLETE
    // cases), the same as delete/move/rename — CommandMatcher no longer
    // matches it at all.

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

    // --- Routing boundaries with the structured agenda path ---------------
    // These phrases must NOT be swallowed by the fast path: they belong to
    // AgendaCommandParser/AgendaIntentRouter, which resolve a real entry id
    // before anything is deleted or moved.

    @Test
    fun plannerDeletesAreLeftToTheAgendaPath() {
        assertNull(CommandMatcher.match("elimina l'appuntamento dal dentista"))
        assertNull(CommandMatcher.match("cancella dentista di domani"))
        assertNull(CommandMatcher.match("togli l'impegno delle 18"))
    }

    @Test
    fun plannerMovesAreLeftToTheAgendaPath() {
        assertNull(CommandMatcher.match("sposta il dentista a venerdì"))
        assertNull(CommandMatcher.match("rimanda la riunione alle 17"))
        assertNull(CommandMatcher.match("anticipa il dentista alle 15"))
    }

    @Test
    fun renamingAnAppointmentIsNotANoteEdit() {
        // "modifica X in Y" is shaped like a note edit; naming a planner item
        // must hand it to the agenda path instead.
        assertNull(CommandMatcher.match("modifica l'appuntamento dal dentista in visita dentistica"))
    }

    @Test
    fun noteEditsWithoutPlannerWordsStillWork() {
        val m = CommandMatcher.match("cambia l'appunto sul gelato in mi piace il cioccolato") as Match.Run
        assertEquals("update_memory", m.call.name)
    }

    @Test
    fun deletingANoteStillGoesToTheMemoryTool() {
        val m = CommandMatcher.match("cancella l'appunto sulla moto") as Match.Run
        assertEquals("forget_memory", m.call.name)
    }

    @Test
    fun creatingAnAppointmentStillUsesTheFastPath() {
        val m = CommandMatcher.match(
            "Aggiungi al calendario dentista domani alle 15",
            now = LocalDateTime.of(2026, 8, 6, 10, 0),
        ) as Match.Run
        assertEquals("add_reminder", m.call.name)
    }

    // --- Re-filing a note into a category --------------------------------

    @Test
    fun movingANoteIntoACategoryIsRecognised() {
        val m = CommandMatcher.match("sposta l'appunto sulla moto in Lavoro e studio") as Match.Run
        assertEquals("move_memory", m.call.name)
        assertEquals("moto", m.call.arguments["text"]?.jsonPrimitive?.content)
        assertEquals("lavoro e studio", m.call.arguments["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun movingAnUnnamedNoteAsksWhichOne() {
        // "questa nota" points at something JARVIS cannot see; it must ask
        // instead of re-filing an arbitrary note.
        val m = CommandMatcher.match("sposta questa nota in Casa") as Match.Ask
        assertEquals("move_memory", m.tool)
        assertEquals("text", m.missing)
        assertEquals("casa", m.partial["category"])
    }

    @Test
    fun aPlannerRescheduleIsNotANoteRefiling() {
        // Same verb, no note cue: must stay with the agenda path.
        assertNull(CommandMatcher.match("sposta il dentista a venerdì"))
    }

    // --- Personal Archive: shopping list ---------------------------------

    @Test
    fun addingAnItemToTheShoppingListWorksThroughSeveralPhrasings() {
        for (q in listOf(
            "Aggiungi filtro olio alle cose da comprare",
            "Devo comprare due lampadine",
            "Ricordami di comprare il latte",
        )) {
            val m = CommandMatcher.match(q)
            assertTrue("no match for: $q", m is Match.Run)
            assertEquals(q, "add_list_item", (m as Match.Run).call.name)
            assertEquals(q, "spesa", m.call.arguments["list"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun whatToBuyListsTheShoppingList() {
        val m = CommandMatcher.match("Cosa devo comprare?") as Match.Run
        assertEquals("list_items", m.call.name)
        assertEquals("spesa", m.call.arguments["list"]?.jsonPrimitive?.content)
    }

    @Test
    fun boughtItemCompletesTheShoppingListEntry() {
        val m = CommandMatcher.match("Ho comprato il casco") as Match.Run
        assertEquals("update_list_item", m.call.name)
        assertEquals("casco", m.call.arguments["item"]?.jsonPrimitive?.content)
        assertEquals("true", m.call.arguments["completed"]?.jsonPrimitive?.content)
    }

    // --- Personal Archive: custom lists -----------------------------------

    @Test
    fun creatingACustomListIsRecognised() {
        val m = CommandMatcher.match("Crea una lista Ricambi moto") as Match.Run
        assertEquals("create_list", m.call.name)
        assertEquals("Ricambi moto", m.call.arguments["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun addingAnItemToACustomListCapturesBothNames() {
        val m = CommandMatcher.match("Aggiungi pastiglie freno alla lista Ricambi moto") as Match.Run
        assertEquals("add_list_item", m.call.name)
        assertEquals("ricambi moto", m.call.arguments["list"]?.jsonPrimitive?.content)
        assertEquals("Pastiglie freno", m.call.arguments["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun removingAnItemFromACustomListCapturesBothNames() {
        val m = CommandMatcher.match("Togli filtro olio dalla lista Ricambi moto") as Match.Run
        assertEquals("remove_list_item", m.call.name)
        assertEquals("ricambi moto", m.call.arguments["list"]?.jsonPrimitive?.content)
        assertEquals("filtro olio", m.call.arguments["item"]?.jsonPrimitive?.content)
    }

    // --- Personal Archive: to-watch ----------------------------------------

    @Test
    fun addingAMovieToTheWatchlistIsRecognised() {
        val m = CommandMatcher.match("Metti Dune nelle cose da vedere") as Match.Run
        assertEquals("create_archive_item", m.call.name)
        assertEquals("to_watch", m.call.arguments["type"]?.jsonPrimitive?.content)
        assertEquals("Dune", m.call.arguments["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun markingAWatchlistItemAsSeenIsRecognised() {
        val m = CommandMatcher.match("Segna Dune come visto") as Match.Run
        assertEquals("update_archive_item", m.call.name)
        assertEquals("to_watch", m.call.arguments["type"]?.jsonPrimitive?.content)
        assertEquals("dune", m.call.arguments["title"]?.jsonPrimitive?.content)
        assertEquals("true", m.call.arguments["completed"]?.jsonPrimitive?.content)
    }

    // --- Personal Archive: quick notes --------------------------------------

    @Test
    fun creatingANoteDerivesAShortTitleFromTheContent() {
        val m = CommandMatcher.match("Crea una nota: il filtro del condizionatore è stato cambiato ad agosto") as Match.Run
        assertEquals("create_archive_item", m.call.name)
        assertEquals("note", m.call.arguments["type"]?.jsonPrimitive?.content)
        assertTrue(m.call.arguments["content"]?.jsonPrimitive?.content?.contains("condizionatore") == true)
    }

    @Test
    fun ordinaryRememberPhrasingStillGoesToMemoryNotTheArchive() {
        // "segnati che"/"prendi nota" are deliberately left on the existing
        // Memory V2 path — see CommandMatcher's noteCreateCall doc comment.
        val m = CommandMatcher.match("Segnati che il pezzo di ricambio compatibile è XYZ") as Match.Run
        assertEquals("remember", m.call.name)
    }

    // --- Search: archive / documents / knowledge ----------------------------

    @Test
    fun whatDidIWriteSearchesThePersonalArchiveNotMemory() {
        val m = CommandMatcher.match("Cosa avevo scritto io sull'OAuth?") as Match.Run
        assertEquals("search_archive", m.call.name)
        assertTrue(m.call.arguments["query"]?.jsonPrimitive?.content?.contains("oauth", ignoreCase = true) == true)
    }

    @Test
    fun searchingPersonalDocumentsIsDistinctFromTheArchive() {
        val m = CommandMatcher.match("Cerca nei miei documenti quello che parla di OAuth") as Match.Run
        assertEquals("search_documents", m.call.name)
    }

    @Test
    fun searchingTheWikiIsDistinctFromTheArchive() {
        val m = CommandMatcher.match("Cerca nella wiki OAuth") as Match.Run
        assertEquals("search_knowledge", m.call.name)
    }
}
