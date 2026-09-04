package com.simone.jarvismobile.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § FASE 2A.2 — the six mandatory scenarios from the request, plus a couple
 * of structural invariants (order preserved, no tool ever silently missing
 * from the classification table).
 */
class RelevantToolSelectorTest {

    private val allTools: List<Pair<String, String>> = listOf(
        "get_time" to "Dice l'ora attuale.",
        "time_until" to "Calcola quanto manca a un orario.",
        "set_alarm" to "Imposta una sveglia.",
        "set_timer" to "Imposta un timer.",
        "battery_status" to "Stato della batteria.",
        "flashlight" to "Accende o spegne la torcia.",
        "add_reminder" to "Aggiunge un promemoria in agenda.",
        "add_task" to "Aggiunge un'attività.",
        "list_agenda" to "Elenca gli impegni.",
        "query_agenda" to "Cerca un impegno per nome.",
        "complete_agenda" to "Segna un impegno completato.",
        "delete_agenda" to "Elimina un impegno.",
        "move_agenda" to "Sposta un impegno.",
        "rename_agenda" to "Rinomina un impegno.",
        "update_agenda_notes" to "Aggiorna le note di un impegno.",
        "create_calendar_event" to "Esporta un evento su Google Calendar.",
        "remember" to "Salva un appunto in memoria.",
        "forget_memory" to "Elimina un appunto.",
        "update_memory" to "Modifica un appunto.",
        "list_memories" to "Elenca gli appunti.",
        "move_memory" to "Sposta un appunto di categoria.",
        "search_memory" to "Cerca negli appunti.",
        "search_knowledge" to "Cerca nella conoscenza importata.",
        "search_documents" to "Cerca nei documenti importati.",
        "read_document_context" to "Legge il contesto di un allegato.",
        "search_images" to "Cerca tra le immagini importate.",
        "search_vault" to "Cerca nel vault Obsidian.",
        "create_archive_item" to "Crea una nota o un elemento da vedere.",
        "read_archive_item" to "Legge un elemento dell'archivio.",
        "update_archive_item" to "Aggiorna un elemento dell'archivio.",
        "delete_archive_item" to "Elimina un elemento dell'archivio.",
        "list_items" to "Elenca gli elementi di una lista.",
        "create_list" to "Crea una nuova lista.",
        "add_list_item" to "Aggiunge un elemento a una lista.",
        "update_list_item" to "Aggiorna un elemento di una lista.",
        "remove_list_item" to "Rimuove un elemento da una lista.",
        "open_app" to "Apre un'app supportata.",
        "open_settings" to "Apre una schermata delle Impostazioni.",
        "prepare_call" to "Prepara una chiamata nel dialer.",
        "compose_sms" to "Prepara un SMS.",
        "reply_message" to "Risponde a una notifica di messaggio.",
        "navigate" to "Avvia la navigazione verso una destinazione.",
        "play_media" to "Riproduce musica o media.",
        "media_control" to "Controlla la riproduzione in corso.",
        "list_notifications" to "Elenca le notifiche attive.",
        "calculate" to "Esegue un calcolo aritmetico.",
        "start_driving_mode" to "Avvia la Modalità Guida.",
        "stop_driving_mode" to "Chiude la Modalità Guida.",
        "set_driving_navigation" to "Imposta la destinazione in Modalità Guida.",
        "start_driving_route" to "Avvia il percorso in Modalità Guida.",
        "show_driving_panel" to "Mostra un pannello della Modalità Guida.",
        "hide_driving_panel" to "Nasconde un pannello della Modalità Guida.",
        "get_weather" to "Meteo reale di oggi o dei prossimi giorni.",
        "get_health_summary" to "Riepilogo settimanale di sonno e frequenza cardiaca da Health Connect.",
    )

    // --- 1. chat semplice -----------------------------------------------------------

    @Test
    fun `simple chat needs no tool catalog at all`() {
        val selected = RelevantToolSelector.select(allTools, "Ciao, come stai oggi?")
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `plain acknowledgement needs no tool catalog`() {
        val selected = RelevantToolSelector.select(allTools, "Va bene, grazie mille!")
        assertTrue(selected.isEmpty())
    }

    // --- 2. comando domotico ---------------------------------------------------------

    @Test
    fun `home-device command surfaces only device tools`() {
        val selected = RelevantToolSelector.select(allTools, "Accendi la torcia, per favore")
        val names = selected.map { it.first }
        assertTrue("flashlight" in names)
        assertTrue("battery_status" in names)
        assertFalse("list_agenda" in names)
        assertFalse("navigate" in names)
    }

    // --- 3. calendario -----------------------------------------------------------------

    @Test
    fun `calendar request surfaces only agenda tools`() {
        val selected = RelevantToolSelector.select(allTools, "Che impegni ho oggi in agenda?")
        val names = selected.map { it.first }
        assertTrue("list_agenda" in names)
        assertTrue("add_reminder" in names)
        assertFalse("flashlight" in names)
        assertFalse("remember" in names)
    }

    // --- 4. memoria --------------------------------------------------------------------

    @Test
    fun `memory request surfaces only memory tools`() {
        val selected = RelevantToolSelector.select(allTools, "Ricordami che mi piace il caffè amaro")
        val names = selected.map { it.first }
        assertTrue("remember" in names)
        assertTrue("list_memories" in names)
        assertFalse("flashlight" in names)
        assertFalse("list_agenda" in names)
    }

    // --- 5. richiesta ambigua ------------------------------------------------------------

    @Test
    fun `ambiguous tool-shaped request falls back to the full catalog`() {
        // "Attivalo" matches ToolIntentGate's action signals (attiv*) but no
        // specific ToolFamily keyword set — exactly the "plausibly needs a
        // tool, but which one is unclear" case the conservative fallback
        // exists for.
        val selected = RelevantToolSelector.select(allTools, "Attivalo per favore, è urgente")
        assertEquals(allTools.size, selected.size)
        assertEquals(allTools, selected)
    }

    @Test
    fun `a personal-task phrasing with no agenda keyword still reaches list_agenda - FASE 2A5`() {
        // § root cause: this phrase used to be gated to empty by
        // ToolIntentGate.shouldClassify() alone (fixed there, not here) - once
        // that gate says "tool-shaped", this object correctly has no specific
        // FAMILY_KEYWORDS match for it either (no literal "agenda"/"impegni"),
        // so it lands in the conservative full-catalog fallback, which DOES
        // include list_agenda/add_task - the model can now actually choose to
        // check, instead of being told upfront that no tool applies at all.
        val selected = RelevantToolSelector.select(allTools, "Settimana prossima devo comprare qualcosa?")
        val names = selected.map { it.first }
        assertTrue("list_agenda" in names)
        assertTrue("add_task" in names)
        assertTrue(selected.isNotEmpty())
    }

    // --- § FASE 2A.5-bis — meteo/salute, capabilities that existed in the app but were
    // never exposed as tools to the conversational engine (root cause of
    // "Che tempo fa domani?"/"Quante ore ho dormito questa settimana?"
    // reaching the model with toolDisponibili=0/53, famiglie=--). -----------------------

    @Test
    fun `a weather question surfaces only the weather tool`() {
        val selected = RelevantToolSelector.select(allTools, "Che tempo fa domani?")
        val names = selected.map { it.first }
        assertTrue("get_weather" in names)
        assertFalse("list_agenda" in names)
        assertFalse("get_health_summary" in names)
    }

    @Test
    fun `a sleep question surfaces only the health tool`() {
        val selected = RelevantToolSelector.select(allTools, "Quante ore ho dormito questa settimana?")
        val names = selected.map { it.first }
        assertTrue("get_health_summary" in names)
        assertFalse("get_weather" in names)
        assertFalse("flashlight" in names)
    }

    @Test
    fun `a weather question is selected even though it is not tool-shaped by ToolIntentGate`() {
        // § root cause: "Che tempo fa domani?" is a plain question, not a
        // command — it never matched ToolIntentGate's action/device-question
        // signals, so the old gate-first order silently zeroed the catalog
        // before any family keyword was even checked. A specific family
        // match must win regardless of that coarser gate.
        assertFalse(com.simone.jarvismobile.core.routing.ToolIntentGate.shouldClassify("Che tempo fa domani?"))
        val selected = RelevantToolSelector.select(allTools, "Che tempo fa domani?")
        assertTrue(selected.isNotEmpty())
    }

    // --- structural invariants -----------------------------------------------------------

    @Test
    fun `every tool actually registered in ToolsModule is classified into exactly one family`() {
        val toolNames = setOf(
            "get_time", "time_until", "set_alarm", "set_timer", "battery_status", "flashlight",
            "add_reminder", "add_task", "list_agenda", "query_agenda", "complete_agenda", "delete_agenda",
            "move_agenda", "rename_agenda", "update_agenda_notes", "create_calendar_event",
            "remember", "forget_memory", "update_memory", "list_memories", "move_memory", "search_memory",
            "search_knowledge", "search_documents", "read_document_context", "search_images", "search_vault",
            "create_archive_item", "read_archive_item", "update_archive_item", "delete_archive_item", "search_archive",
            "list_items", "create_list", "add_list_item", "update_list_item", "remove_list_item",
            "open_app", "open_settings", "prepare_call", "compose_sms", "reply_message",
            "navigate", "play_media", "media_control", "list_notifications", "calculate",
            "start_driving_mode", "stop_driving_mode", "set_driving_navigation", "start_driving_route",
            "show_driving_panel", "hide_driving_panel", "get_weather", "get_health_summary",
        )
        assertEquals(55, toolNames.size)
        val ambiguousResult = RelevantToolSelector.select(
            toolNames.map { it to "d" },
            "Attivalo per favore, è urgente",
        )
        // Every one of these 53 names must survive the ambiguous fallback —
        // if any were missing here it would mean it's unclassified AND was
        // (incorrectly) excluded, contradicting the "never drop an unknown
        // tool" rule in the class doc comment.
        assertEquals(toolNames, ambiguousResult.map { it.first }.toSet())
    }

    @Test
    fun `an unclassified future tool is never dropped by a specific family match`() {
        val withUnknownTool = allTools + ("brand_new_tool" to "Not yet in the classification table.")
        val selected = RelevantToolSelector.select(withUnknownTool, "Accendi la torcia")
        assertTrue("brand_new_tool" in selected.map { it.first })
        assertTrue("flashlight" in selected.map { it.first })
        assertFalse("list_agenda" in selected.map { it.first })
    }

    @Test
    fun `selection preserves the original catalog order`() {
        val selected = RelevantToolSelector.select(allTools, "Che impegni ho oggi in agenda?")
        val expectedOrder = allTools.map { it.first }.filter { it in selected.map { p -> p.first } }
        assertEquals(expectedOrder, selected.map { it.first })
    }

    // --- § FASE 2A.5 diagnostica: familyOf/familiesOf ------------------------------------

    @Test
    fun `familyOf reports the classified family, null for an unknown tool`() {
        assertEquals(ToolFamily.AGENDA, RelevantToolSelector.familyOf("list_agenda"))
        assertEquals(ToolFamily.DEVICE, RelevantToolSelector.familyOf("flashlight"))
        assertEquals(null, RelevantToolSelector.familyOf("brand_new_tool"))
    }

    @Test
    fun `familiesOf collapses a selection into its distinct families, for diagnostics only`() {
        val selected = RelevantToolSelector.select(allTools, "Accendi la torcia")
        val families = RelevantToolSelector.familiesOf(selected)
        assertEquals(setOf(ToolFamily.DEVICE), families)
    }

    // --- § FASE 2A.5-bis §3 — grounding policy, generalizable across families -----------

    @Test
    fun `weather and health are grounded families - never answerable from the model's own knowledge`() {
        assertTrue(ToolFamily.WEATHER in GROUNDED_FAMILIES)
        assertTrue(ToolFamily.HEALTH in GROUNDED_FAMILIES)
        assertTrue(ToolFamily.AGENDA in GROUNDED_FAMILIES)
        assertTrue(ToolFamily.MEMORY in GROUNDED_FAMILIES)
        assertTrue(ToolFamily.ARCHIVE in GROUNDED_FAMILIES)
    }

    @Test
    fun `families with no runtime-personal data are not grounded`() {
        assertFalse(ToolFamily.UTILITY in GROUNDED_FAMILIES)
        assertFalse(ToolFamily.KNOWLEDGE in GROUNDED_FAMILIES)
    }

    // --- § FASE 2A.4 — sequential turns, not just isolated calls ------------------------

    @Test
    fun `TEST CORE then a device command, back to back - selection never leaks between them`() {
        // § root cause audit for "Accendi la luce della camera" -> "'TEST CORE'
        // è stato eseguito correttamente": this object is a stateless singleton
        // (no var/mutable field anywhere in it) so it CANNOT itself carry state
        // from one select() call to the next - this test pins that guarantee
        // explicitly rather than only asserting it by code inspection. The real
        // bug lived one layer down, in the native model's own conversation
        // object (LitertLmEngine) - fixed in JarvisBrain, not here; see the
        // phase report for why this layer was never actually the culprit.
        val turn1 = RelevantToolSelector.select(allTools, "Rispondi solo: TEST CORE")
        val turn2 = RelevantToolSelector.select(allTools, "Accendi la luce della camera")
        assertTrue(turn1.isEmpty())
        val names2 = turn2.map { it.first }
        assertTrue("flashlight" in names2)
        assertTrue("battery_status" in names2)
        assertFalse(names2.any { it.contains("agenda") })
        // Calling it a second time in isolation (no turn 1 before it) must
        // produce the exact same result - proof the first call left no trace.
        val turn2Isolated = RelevantToolSelector.select(allTools, "Accendi la luce della camera")
        assertEquals(turn2, turn2Isolated)
    }

    @Test
    fun `an agenda question then a device command, back to back - selection never leaks between them`() {
        val turn1 = RelevantToolSelector.select(allTools, "Che impegni ho oggi?")
        val turn2 = RelevantToolSelector.select(allTools, "Accendi la luce della camera")
        val names1 = turn1.map { it.first }
        val names2 = turn2.map { it.first }
        assertTrue("list_agenda" in names1)
        assertFalse("flashlight" in names1)
        assertTrue("flashlight" in names2)
        assertFalse("list_agenda" in names2)
    }

    @Test
    fun `the reverse order - device command then an agenda question - is equally isolated`() {
        val turn1 = RelevantToolSelector.select(allTools, "Accendi la luce della camera")
        val turn2 = RelevantToolSelector.select(allTools, "Che impegni ho oggi?")
        val names1 = turn1.map { it.first }
        val names2 = turn2.map { it.first }
        assertTrue("flashlight" in names1)
        assertFalse("list_agenda" in names1)
        assertTrue("list_agenda" in names2)
        assertFalse("flashlight" in names2)
    }

    // --- § FASE 2A.5-bis §6 — cross-turn contamination, extended to the two new families --

    @Test
    fun `a device command then a sleep question - selection never leaks between them`() {
        val turn1 = RelevantToolSelector.select(allTools, "Accendi la torcia")
        val turn2 = RelevantToolSelector.select(allTools, "Quante ore ho dormito questa settimana?")
        assertTrue("flashlight" in turn1.map { it.first })
        assertFalse("get_health_summary" in turn1.map { it.first })
        assertTrue("get_health_summary" in turn2.map { it.first })
        assertFalse("flashlight" in turn2.map { it.first })
    }

    @Test
    fun `a sleep question then a device command - selection never leaks between them`() {
        val turn1 = RelevantToolSelector.select(allTools, "Quante ore ho dormito questa settimana?")
        val turn2 = RelevantToolSelector.select(allTools, "Accendi la torcia")
        assertTrue("get_health_summary" in turn1.map { it.first })
        assertFalse("flashlight" in turn1.map { it.first })
        assertTrue("flashlight" in turn2.map { it.first })
        assertFalse("get_health_summary" in turn2.map { it.first })
    }

    @Test
    fun `a weather question then a sleep question - selection never leaks between them`() {
        val turn1 = RelevantToolSelector.select(allTools, "Che tempo fa domani?")
        val turn2 = RelevantToolSelector.select(allTools, "Quante ore ho dormito questa settimana?")
        assertTrue("get_weather" in turn1.map { it.first })
        assertFalse("get_health_summary" in turn1.map { it.first })
        assertTrue("get_health_summary" in turn2.map { it.first })
        assertFalse("get_weather" in turn2.map { it.first })
    }

    @Test
    fun `an agenda question then a weather question - selection never leaks between them`() {
        val turn1 = RelevantToolSelector.select(allTools, "Che impegni ho domani?")
        val turn2 = RelevantToolSelector.select(allTools, "Che tempo fa domani?")
        assertTrue("list_agenda" in turn1.map { it.first })
        assertFalse("get_weather" in turn1.map { it.first })
        assertTrue("get_weather" in turn2.map { it.first })
        assertFalse("list_agenda" in turn2.map { it.first })
    }

    @Test
    fun `TEST CORE then a sleep question - selection never leaks between them`() {
        val turn1 = RelevantToolSelector.select(allTools, "Rispondi solo: TEST CORE")
        val turn2 = RelevantToolSelector.select(allTools, "Quante ore ho dormito questa settimana?")
        assertTrue(turn1.isEmpty())
        assertTrue("get_health_summary" in turn2.map { it.first })
        // Calling it a second time in isolation must give the exact same result.
        val turn2Isolated = RelevantToolSelector.select(allTools, "Quante ore ho dormito questa settimana?")
        assertEquals(turn2, turn2Isolated)
    }
}
