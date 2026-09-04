package com.simone.jarvismobile.core.tools

import com.simone.jarvismobile.core.routing.ToolIntentGate

/**
 * Coarse-grained grouping of today's full tool registry (`ToolsModule`, 53
 * tools) by name — the stable identifier every [Tool] already exposes, never
 * its free-text [Tool.description]. Used only to decide which tools belong
 * in a given turn's prompt catalog (§ FASE 2A.2); it changes nothing about
 * how a tool is resolved or executed — [ToolRegistry] remains the sole
 * authority for that.
 */
enum class ToolFamily {
    TIME, DEVICE, AGENDA, MEMORY, KNOWLEDGE, ARCHIVE, SYSTEM_APP, COMMUNICATION, MEDIA, DRIVING, UTILITY
}

/**
 * Deterministic, keyword-based selection of which registered tools are
 * plausibly relevant to one user request (§ FASE 2A.2 — "prima dell'LLM
 * determina deterministicamente quali tool/famiglie di tool sono
 * plausibilmente pertinenti"). Mirrors
 * [com.simone.jarvismobile.core.snapshot.RelevantContextSelector]'s "simple
 * deterministic rules, never a second LLM" philosophy — that selector
 * narrows the *personal-context snapshot* (time/place/agenda/...); this one
 * narrows the *tool catalog* instead, a different concern with no shared
 * state, so it is a new object rather than an extension of that one. Pure,
 * no I/O, no Android dependency — testable in isolation.
 *
 * [ToolIntentGate.shouldClassify] — the same deterministic gate the
 * fast-path classifier already uses to decide whether a request is
 * tool-shaped at all — is reused here as the first cut: a request with no
 * tool-shaped signal whatsoever (an ordinary "Ciao, come stai?") needs no
 * tool catalog in the prompt at all, never a second parallel gate.
 *
 * A tool name absent from [FAMILY_BY_TOOL_NAME] (e.g. a future tool added to
 * the registry before this table is updated) is always treated as relevant —
 * silently losing tool-calling capability for an unclassified tool would be
 * worse than an occasionally larger catalog, which the existing wire-level
 * truncation (`RemoteAiEngine.truncateSystemPromptForWire`) already guards
 * as a safety net, never the primary strategy here.
 */
object RelevantToolSelector {

    private val FAMILY_BY_TOOL_NAME: Map<String, ToolFamily> = buildMap {
        listOf("get_time", "time_until", "set_alarm", "set_timer").forEach { put(it, ToolFamily.TIME) }
        listOf("battery_status", "flashlight").forEach { put(it, ToolFamily.DEVICE) }
        listOf(
            "add_reminder", "add_task", "list_agenda", "query_agenda", "complete_agenda",
            "delete_agenda", "move_agenda", "rename_agenda", "update_agenda_notes", "create_calendar_event",
        ).forEach { put(it, ToolFamily.AGENDA) }
        listOf(
            "remember", "forget_memory", "update_memory", "list_memories", "move_memory", "search_memory",
        ).forEach { put(it, ToolFamily.MEMORY) }
        listOf(
            "search_knowledge", "search_documents", "read_document_context", "search_images", "search_vault",
        ).forEach { put(it, ToolFamily.KNOWLEDGE) }
        listOf(
            "create_archive_item", "read_archive_item", "update_archive_item", "delete_archive_item",
            "search_archive", "list_items", "create_list", "add_list_item", "update_list_item", "remove_list_item",
        ).forEach { put(it, ToolFamily.ARCHIVE) }
        listOf("open_app", "open_settings", "list_notifications").forEach { put(it, ToolFamily.SYSTEM_APP) }
        listOf("compose_sms", "prepare_call", "reply_message").forEach { put(it, ToolFamily.COMMUNICATION) }
        listOf("play_media", "media_control").forEach { put(it, ToolFamily.MEDIA) }
        listOf(
            "navigate", "start_driving_mode", "stop_driving_mode", "set_driving_navigation",
            "start_driving_route", "show_driving_panel", "hide_driving_panel",
        ).forEach { put(it, ToolFamily.DRIVING) }
        put("calculate", ToolFamily.UTILITY)
    }

    private val TIME_KEYWORDS = setOf(
        "sveglia", "svegliami", "timer", "allarme", "che ore", "che ora", "orario",
        "quanto manca", "fra quanto", "tra quanto",
    )
    private val DEVICE_KEYWORDS = setOf(
        "torcia", "luce", "accendi", "spegni", "batteria", "carica", "caricando", "percentuale",
    )
    private val AGENDA_KEYWORDS = setOf(
        "appuntamento", "appuntamenti", "impegno", "impegni", "agenda", "riunione", "riunioni",
        "promemoria", "evento", "eventi", "calendario", "scadenza", "scadenze", "attivita", "task",
    )
    private val MEMORY_KEYWORDS = setOf(
        "ricorda", "ricordami", "ricordati", "memoria", "appunto", "appunti", "dimentica", "annota",
    )
    private val KNOWLEDGE_KEYWORDS = setOf(
        "manuale", "guida a", "come si fa", "come funziona", "significa", "vault", "documento",
        "documenti", "wiki", "conoscenza",
    )
    private val ARCHIVE_KEYWORDS = setOf(
        "lista", "liste", "archivio", "nota", "note", "spesa", "da vedere", "elenco",
    )
    private val SYSTEM_APP_KEYWORDS = setOf("apri", "app", "impostazioni", "notifica", "notifiche")
    private val COMMUNICATION_KEYWORDS = setOf(
        "chiama", "telefona", "sms", "messaggio", "numero di telefono", "rispondi a",
    )
    private val MEDIA_KEYWORDS = setOf("musica", "riproduci", "play", "pausa", "brano", "spotify", "canzone")
    private val DRIVING_KEYWORDS = setOf(
        "naviga", "navigazione", "portami", "guidami", "percorso", "guido", "guidando",
        "strada", "parcheggio", "modalita guida", "traffico", "autostrada",
    )
    private val UTILITY_KEYWORDS = setOf("calcola", "quanto fa", "quanto e", "somma", "diviso", "moltiplica")

    private val FAMILY_KEYWORDS: Map<ToolFamily, Set<String>> = mapOf(
        ToolFamily.TIME to TIME_KEYWORDS,
        ToolFamily.DEVICE to DEVICE_KEYWORDS,
        ToolFamily.AGENDA to AGENDA_KEYWORDS,
        ToolFamily.MEMORY to MEMORY_KEYWORDS,
        ToolFamily.KNOWLEDGE to KNOWLEDGE_KEYWORDS,
        ToolFamily.ARCHIVE to ARCHIVE_KEYWORDS,
        ToolFamily.SYSTEM_APP to SYSTEM_APP_KEYWORDS,
        ToolFamily.COMMUNICATION to COMMUNICATION_KEYWORDS,
        ToolFamily.MEDIA to MEDIA_KEYWORDS,
        ToolFamily.DRIVING to DRIVING_KEYWORDS,
        ToolFamily.UTILITY to UTILITY_KEYWORDS,
    )

    /**
     * [availableTools] is `ToolRunner.available()`'s live (name, description)
     * pairs — the very same catalog the full prompt used to embed
     * unconditionally, never a second registry. Returns the subset plausibly
     * relevant to [userText], preserving [availableTools]' original order:
     *
     *  - [userText] has no tool-shaped signal at all ([ToolIntentGate] says
     *    so) → empty: a plain "Ciao, come stai?" needs no tool catalog.
     *  - One or more [ToolFamily] keyword sets match → only tools in those
     *    families, plus any tool [FAMILY_BY_TOOL_NAME] does not yet classify
     *    (see the class doc comment on why those are never dropped).
     *  - [ToolIntentGate] says a tool is plausibly needed but no specific
     *    family matched (a genuinely ambiguous request, e.g. "Attivalo per
     *    favore") → every tool: the conservative fallback that never costs
     *    capability. The existing wire-level truncation is what keeps this
     *    within the protocol limit on that rarer path, not this function.
     */
    fun select(availableTools: List<Pair<String, String>>, userText: String): List<Pair<String, String>> {
        if (!ToolIntentGate.shouldClassify(userText)) return emptyList()

        val normalized = normalize(userText)
        val matchedFamilies = FAMILY_KEYWORDS.filterValues { keywords -> keywords.any(normalized::contains) }.keys

        if (matchedFamilies.isEmpty()) return availableTools

        return availableTools.filter { (name, _) ->
            val family = FAMILY_BY_TOOL_NAME[name]
            family == null || family in matchedFamilies
        }
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
}
